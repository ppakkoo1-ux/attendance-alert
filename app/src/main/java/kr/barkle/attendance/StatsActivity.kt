package kr.barkle.attendance

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.time.LocalDate

/** 기간별 출결 통계 */
object Stats {
    /** counts[categoryIndex * 4 + reasonIndex] */
    class Row(val student: Student, val counts: IntArray) {
        val total get() = counts.sum()
    }

    fun compute(db: Db, start: LocalDate, end: LocalDate): Pair<List<Row>, List<Record>> {
        val recs = db.recordsBetween(start.toString(), end.toString())
        val smap = db.studentMap()
        val ids = (db.students().map { it.id } + recs.map { it.studentId }).distinct()
        val bySid = recs.groupBy { it.studentId }
        val rows = ids.mapNotNull { smap[it] }
            .sortedWith(compareBy<Student>({ !it.active }, { it.num }))
            .map { s ->
                val arr = IntArray(Att.CATEGORIES.size * Att.REASONS.size)
                for (r in bySid[s.id].orEmpty()) {
                    val ci = Att.CATEGORIES.indexOf(r.category)
                    val ri = Att.REASONS.indexOf(r.reason)
                    if (ci >= 0 && ri >= 0) arr[ci * Att.REASONS.size + ri]++
                }
                Row(s, arr)
            }
        return rows to recs
    }

    fun summary(counts: IntArray): String {
        val parts = mutableListOf<String>()
        Att.CATEGORIES.forEachIndexed { ci, c ->
            Att.REASONS.forEachIndexed { ri, r ->
                val n = counts[ci * Att.REASONS.size + ri]
                if (n > 0) parts.add("${Att.label(c, r)} $n")
            }
        }
        return parts.joinToString(" · ")
    }
}

class StatsActivity : Activity() {

    private var start: LocalDate = Att.schoolYearStart(LocalDate.now())
    private var end: LocalDate = LocalDate.now()
    private var byStudent = false  // 기본: 날짜순
    private lateinit var root: LinearLayout
    private val db by lazy { Db.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    private fun pick(d: LocalDate, set: (LocalDate) -> Unit) {
        DatePickerDialog(this, { _, y, m, day -> set(LocalDate.of(y, m + 1, day)); render() },
            d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(
            hrow(
                weighted(btn(start.toString()) { pick(start) { start = it } }),
                label("  ~  ", 16f),
                weighted(btn(end.toString()) { pick(end) { end = it } }),
            )
        )
        root.addView(
            hrow(
                weighted(btn("지난 달") {
                    val t = LocalDate.now().minusMonths(1); start = t.withDayOfMonth(1); end = t.withDayOfMonth(t.lengthOfMonth()); render()
                }),
                weighted(btn("이번 달") {
                    val t = LocalDate.now(); start = t.withDayOfMonth(1); end = t; render()
                }),
                weighted(btn("학년도") {
                    val t = LocalDate.now(); start = Att.schoolYearStart(t); end = t; render()
                }),
            )
        )
        root.addView(btn("월별 출결 현황 PDF 저장") { exportPdf() })
        if (start.isAfter(end)) {
            root.addView(label("시작일이 종료일보다 늦습니다.", 14f, color = "#B00020"))
            return
        }

        val (rows, recs) = Stats.compute(db, start, end)
        val smap = db.studentMap()

        root.addView(section("합계 (${recs.size}건)"))
        val total = IntArray(Att.CATEGORIES.size * Att.REASONS.size)
        rows.forEach { r -> r.counts.forEachIndexed { i, n -> total[i] += n } }
        root.addView(label(Stats.summary(total).ifBlank { "기록 없음" }, 15f))

        root.addView(
            hrow(
                weighted(section(if (byStudent) "학생별" else "날짜순")),
                btn(if (byStudent) "날짜순으로 보기" else "학생별로 보기") { byStudent = !byStudent; render() },
            )
        )
        if (byStudent) {
            val withRecs = rows.filter { it.total > 0 }
            if (withRecs.isEmpty()) root.addView(label("기록 없음", 14f, color = "#666666"))
            for (r in withRecs) {
                val tag = if (r.student.active) "" else " (명단 제외)"
                root.addView(label("${r.student.num}번 ${r.student.name}$tag", 15f, true).apply { setPadding(0, dp(6), 0, 0) })
                root.addView(label(Stats.summary(r.counts), 14f))
            }
        } else {
            if (recs.isEmpty()) root.addView(label("기록 없음", 14f, color = "#666666"))
            val byDate = recs.sortedWith(compareBy<Record>({ it.date }, { smap[it.studentId]?.num ?: 0 })).groupBy { it.date }
            for ((date, list) in byDate) {
                root.addView(label(Att.short(date), 15f, true).apply { setPadding(0, dp(8), 0, 0) })
                for (r in list) {
                    val st = smap[r.studentId]
                    val per = if (r.periods.isNotBlank() && r.category != "결석") " (${r.periods})" else ""
                    val memo = if (r.memo.isNotBlank()) "  ${r.memo}" else ""
                    root.addView(label("${st?.num ?: "?"}번 ${st?.name ?: "?"}  ${r.label}$per$memo", 14f))
                }
            }
        }

        val unsubmitted = recs.filter { it.needsDoc && !it.docSubmitted }
        root.addView(section("서류 미제출 (${unsubmitted.size}건)"))
        if (unsubmitted.isEmpty()) root.addView(label("없음", 14f, color = "#666666"))
        for (r in unsubmitted) {
            val s = smap[r.studentId]
            root.addView(label("${Att.short(r.date)}  ${s?.num ?: "?"}번 ${s?.name ?: "?"}  ${r.label}", 15f))
        }
    }

    private fun exportPdf() {
        if (start.isAfter(end)) return
        try {
            val recs = db.recordsBetween(start.toString(), end.toString())
            val uri = PdfExporter.export(this, start, end, recs, db.studentMap())
            Toast.makeText(this, "다운로드/출결알림 폴더에 저장했습니다", Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (e: Exception) {
                // PDF 뷰어가 없으면 저장만
            }
        } catch (e: Exception) {
            Toast.makeText(this, "PDF 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
