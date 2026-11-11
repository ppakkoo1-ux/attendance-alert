package kr.barkle.attendance

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** 출결 구분·사유와 날짜 표시 */
object Att {
    val CATEGORIES = listOf("결석", "지각", "조퇴", "결과")
    val REASONS = listOf("인정", "질병", "미인정", "기타")

    /** 나이스 표기: 인정 → 출석인정결석, 질병결석, 미인정지각 … */
    fun label(category: String, reason: String) =
        (if (reason == "인정") "출석인정" else reason) + category

    // ---------- 교시 ----------
    const val HOMEROOM = 0   // 조회
    const val END = 99       // 종례

    fun periodName(p: Int) = when (p) {
        HOMEROOM -> "조회"
        END -> "종례"
        else -> "${p}교시"
    }

    /**
     * 결시교시 문자열 (나이스 월별 출결 현황 형식)
     * 결석: 조회~n교시,종례 / 지각: 조회~to / 조퇴: from~n교시,종례 / 결과: from~to
     */
    fun periodsText(category: String, n: Int, from: Int, to: Int): String {
        val list: List<Int> = when (category) {
            "결석" -> listOf(HOMEROOM) + (1..n).toList() + END
            "지각" -> (HOMEROOM..to.coerceIn(HOMEROOM, n)).toList()
            "조퇴" -> if (from == END) listOf(END) else (from..n).toList() + END
            "결과" -> (from..maxOf(from, to)).toList()
            else -> emptyList()
        }
        return list.joinToString(",") { periodName(it) }
    }

    /** 출결서류가 필요한 기록: 모든 결석, 인정지각, 인정조퇴, 인정결과 */
    fun needsDoc(category: String, reason: String) = when (category) {
        "결석" -> true
        "지각", "조퇴", "결과" -> reason == "인정"
        else -> false
    }

    private fun dow(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)

    fun pretty(d: LocalDate) = "${d.year}. ${d.monthValue}. ${d.dayOfMonth}. (${dow(d)})"

    fun short(date: String): String {
        val d = LocalDate.parse(date)
        return "${d.monthValue}/${d.dayOfMonth}(${dow(d)})"
    }

    /** 학년도 시작일(3월 1일) */
    fun schoolYearStart(d: LocalDate): LocalDate =
        if (d.monthValue >= 3) LocalDate.of(d.year, 3, 1) else LocalDate.of(d.year - 1, 3, 1)

    fun parseTime(s: String): LocalTime? {
        val m = Regex("^\\s*(\\d{1,2})\\s*:\\s*(\\d{2})\\s*$").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h !in 0..23 || mi !in 0..59) return null
        return LocalTime.of(h, mi)
    }

    fun fmtTime(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
}

/** 설정값 */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    const val DEFAULT_CHECK_TIMES = "08:50, 09:00, 12:30"
    const val DEFAULT_DOC_TIME = "08:35"

    fun checkTimesText(c: Context): String = sp(c).getString("check_times", DEFAULT_CHECK_TIMES) ?: DEFAULT_CHECK_TIMES
    fun docTimeText(c: Context): String = sp(c).getString("doc_time", DEFAULT_DOC_TIME) ?: DEFAULT_DOC_TIME
    fun holidaysText(c: Context): String = sp(c).getString("holidays", "") ?: ""

    fun checkTimes(c: Context): List<LocalTime> =
        checkTimesText(c).split(",", " ", "\n").mapNotNull { Att.parseTime(it) }.distinct().sorted().take(8)

    fun docTime(c: Context): LocalTime = Att.parseTime(docTimeText(c)) ?: LocalTime.of(8, 35)

    const val DEFAULT_PERIODS = "6, 7, 6, 7, 6"

    fun schoolName(c: Context): String = sp(c).getString("school_name", "") ?: ""
    fun grade(c: Context): String = sp(c).getString("grade", "") ?: ""
    fun classNo(c: Context): String = sp(c).getString("class_no", "") ?: ""
    fun periodsText(c: Context): String = sp(c).getString("periods", DEFAULT_PERIODS) ?: DEFAULT_PERIODS

    /** 요일별(월~금) 교시 수 */
    fun periodsByWeekday(c: Context): List<Int> {
        val nums = periodsText(c).split(",", " ").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..10 }
        return List(5) { nums.getOrElse(it) { 7 } }
    }

    fun periodsOn(c: Context, d: LocalDate): Int =
        if (d.dayOfWeek.value <= 5) periodsByWeekday(c)[d.dayOfWeek.value - 1] else 7

    fun save(
        c: Context, checkTimes: String, docTime: String,
        schoolName: String, grade: String, classNo: String, periods: String,
    ) {
        sp(c).edit()
            .putString("check_times", checkTimes)
            .putString("doc_time", docTime)
            .putString("school_name", schoolName)
            .putString("grade", grade)
            .putString("class_no", classNo)
            .putString("periods", periods)
            .apply()
    }

    fun setHolidays(c: Context, text: String) {
        sp(c).edit().putString("holidays", text).apply()
    }
}

/** 등교일 판단: 토·일, 설정의 휴일/방학 제외 */
object SchoolCalendar {
    private val DATE_RE = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")

    fun parseHolidays(text: String): List<ClosedRange<LocalDate>> {
        val out = mutableListOf<ClosedRange<LocalDate>>()
        for (raw in text.lines()) {
            val line = raw.substringBefore("#")
            val dates = DATE_RE.findAll(line).mapNotNull {
                try {
                    LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
                } catch (e: Exception) {
                    null
                }
            }.toList()
            when {
                dates.size == 1 -> out.add(dates[0]..dates[0])
                dates.size >= 2 -> {
                    val a = minOf(dates[0], dates[1])
                    val b = maxOf(dates[0], dates[1])
                    out.add(a..b)
                }
            }
        }
        return out
    }

    fun isSchoolDay(c: Context, d: LocalDate): Boolean {
        if (d.dayOfWeek.value >= 6) return false
        return parseHolidays(Prefs.holidaysText(c)).none { d in it }
    }
}

// ---------- 화면 구성 도우미 ----------

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.label(s: String, size: Float = 16f, bold: Boolean = false, color: String = "#222222"): TextView =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.section(s: String): TextView = label(s, 17f, true).apply {
    setPadding(0, dp(18), 0, dp(6))
}

fun Context.btn(s: String, onClick: () -> Unit): Button = Button(this).apply {
    text = s
    isAllCaps = false
    setOnClickListener { onClick() }
}

fun Context.hrow(vararg views: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    views.forEach { addView(it) }
}

fun weighted(v: View, weight: Float = 1f): View {
    v.layoutParams = LinearLayout.LayoutParams(0, WRAP, weight)
    return v
}

fun rounded(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = Color.TRANSPARENT) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }
