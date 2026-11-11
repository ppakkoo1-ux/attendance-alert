package kr.barkle.attendance

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Student(val id: Long, val num: Int, val name: String, val active: Boolean)

/** 날짜별 조회 상태: missing = 조회 때 없음, arrived = 이후 출석 확인 */
data class Daily(val studentId: Long, val missing: Boolean, val arrived: Boolean)

data class Record(
    val id: Long,
    val date: String,
    val studentId: Long,
    val category: String,
    val reason: String,
    val memo: String,
    val docSubmitted: Boolean,
    val docAlerted: Boolean,
) {
    val label get() = Att.label(category, reason)
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "attendance.db", null, 1) {

    companion object {
        @Volatile
        private var inst: Db? = null

        fun get(ctx: Context): Db =
            inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE students(id INTEGER PRIMARY KEY AUTOINCREMENT, num INTEGER NOT NULL, " +
                "name TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL(
            "CREATE TABLE daily(date TEXT NOT NULL, student_id INTEGER NOT NULL, " +
                "missing INTEGER NOT NULL DEFAULT 0, arrived INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(date, student_id))"
        )
        db.execSQL(
            "CREATE TABLE records(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, " +
                "student_id INTEGER NOT NULL, category TEXT NOT NULL, reason TEXT NOT NULL, " +
                "memo TEXT NOT NULL DEFAULT '', doc_submitted INTEGER NOT NULL DEFAULT 0, " +
                "doc_alerted INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_records_date ON records(date)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun cv(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
        for ((k, v) in pairs) {
            when (v) {
                null -> putNull(k)
                is Int -> put(k, v)
                is Long -> put(k, v)
                is String -> put(k, v)
                is Boolean -> put(k, if (v) 1 else 0)
                else -> put(k, v.toString())
            }
        }
    }

    private inline fun <T> Cursor.collect(row: (Cursor) -> T): List<T> = use {
        val list = mutableListOf<T>()
        while (it.moveToNext()) list.add(row(it))
        list
    }

    // ---------- 학생 ----------

    fun students(activeOnly: Boolean = true): List<Student> =
        readableDatabase.rawQuery(
            "SELECT id, num, name, active FROM students" +
                (if (activeOnly) " WHERE active=1" else "") + " ORDER BY num, name",
            null
        ).collect { Student(it.getLong(0), it.getInt(1), it.getString(2), it.getInt(3) == 1) }

    fun studentMap(): Map<Long, Student> = students(false).associateBy { it.id }

    /** 명단 저장: 같은 이름은 기존 학생으로 이어서 사용(기록 유지), 빠진 학생은 비활성 */
    fun saveRoster(list: List<Pair<Int, String>>) {
        val w = writableDatabase
        val existing = students(false)
        w.beginTransaction()
        try {
            val keep = mutableSetOf<Long>()
            for ((num, name) in list) {
                val e = existing.firstOrNull { it.num == num && it.name == name && it.id !in keep }
                    ?: existing.firstOrNull { it.name == name && it.id !in keep }
                if (e != null) {
                    w.update("students", cv("num" to num, "active" to 1), "id=?", arrayOf(e.id.toString()))
                    keep.add(e.id)
                } else {
                    keep.add(w.insert("students", null, cv("num" to num, "name" to name, "active" to 1)))
                }
            }
            for (s in existing) {
                if (s.id !in keep) w.update("students", cv("active" to 0), "id=?", arrayOf(s.id.toString()))
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    // ---------- 조회 상태 ----------

    fun daily(date: String): Map<Long, Daily> =
        readableDatabase.rawQuery(
            "SELECT student_id, missing, arrived FROM daily WHERE date=?", arrayOf(date)
        ).collect { Daily(it.getLong(0), it.getInt(1) == 1, it.getInt(2) == 1) }
            .associateBy { it.studentId }

    fun setMissing(date: String, sid: Long, missing: Boolean) {
        if (missing) {
            writableDatabase.insertWithOnConflict(
                "daily", null,
                cv("date" to date, "student_id" to sid, "missing" to 1, "arrived" to 0),
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } else {
            writableDatabase.delete("daily", "date=? AND student_id=?", arrayOf(date, sid.toString()))
        }
    }

    fun setArrived(date: String, sid: Long, arrived: Boolean) {
        writableDatabase.update(
            "daily", cv("arrived" to arrived), "date=? AND student_id=?", arrayOf(date, sid.toString())
        )
    }

    /** 조회 때 없었고, 아직 출석 확인도 결석 기록도 없는 학생 */
    fun unresolved(date: String): List<Student> =
        readableDatabase.rawQuery(
            "SELECT s.id, s.num, s.name, s.active FROM daily d JOIN students s ON s.id=d.student_id " +
                "WHERE d.date=? AND d.missing=1 AND d.arrived=0 AND NOT EXISTS (" +
                "SELECT 1 FROM records r WHERE r.date=d.date AND r.student_id=d.student_id AND r.category='결석') " +
                "ORDER BY s.num",
            arrayOf(date)
        ).collect { Student(it.getLong(0), it.getInt(1), it.getString(2), it.getInt(3) == 1) }

    // ---------- 출결 기록 ----------

    private fun readRecords(where: String, args: Array<String>): List<Record> =
        readableDatabase.rawQuery(
            "SELECT id, date, student_id, category, reason, memo, doc_submitted, doc_alerted " +
                "FROM records WHERE $where ORDER BY date, id",
            args
        ).collect {
            Record(
                it.getLong(0), it.getString(1), it.getLong(2), it.getString(3), it.getString(4),
                it.getString(5), it.getInt(6) == 1, it.getInt(7) == 1
            )
        }

    fun records(date: String) = readRecords("date=?", arrayOf(date))

    fun recordsBetween(start: String, end: String) = readRecords("date>=? AND date<=?", arrayOf(start, end))

    /** 오늘 이전 날짜의 기록 중 서류 안내 알림을 아직 안 보냈고 서류도 미제출인 것 */
    fun pendingDocAlerts(today: String) = readRecords("date<? AND doc_alerted=0 AND doc_submitted=0", arrayOf(today))

    fun addRecord(date: String, sid: Long, category: String, reason: String, memo: String) {
        writableDatabase.insert(
            "records", null,
            cv(
                "date" to date, "student_id" to sid, "category" to category,
                "reason" to reason, "memo" to memo
            )
        )
        // 지각은 등교한 것이므로 조회 미확인 상태를 출석으로 처리
        if (category == "지각") setArrived(date, sid, true)
    }

    fun setDocSubmitted(id: Long, submitted: Boolean) {
        writableDatabase.update("records", cv("doc_submitted" to submitted), "id=?", arrayOf(id.toString()))
    }

    fun markAlerted(ids: List<Long>) {
        for (id in ids) writableDatabase.update("records", cv("doc_alerted" to 1), "id=?", arrayOf(id.toString()))
    }

    fun deleteRecord(id: Long) {
        writableDatabase.delete("records", "id=?", arrayOf(id.toString()))
    }
}
