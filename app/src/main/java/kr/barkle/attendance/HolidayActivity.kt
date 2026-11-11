package kr.barkle.attendance

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.YearMonth
import java.util.TreeSet

/** 달력에서 휴일·방학 지정. 누르면 휴일 지정/해제, 기간은 버튼으로. */
class HolidayActivity : Activity() {

    private var month: YearMonth = YearMonth.now()
    private val dates = TreeSet<LocalDate>()
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "휴일·방학 지정"
        for (r in SchoolCalendar.parseHolidays(Prefs.holidaysText(this))) {
            var d = r.start
            var guard = 0
            while (!d.isAfter(r.endInclusive) && guard < 800) {
                if (d.dayOfWeek.value <= 5) dates.add(d)
                d = d.plusDays(1)
                guard++
            }
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    private fun save() {
        Prefs.setHolidays(this, dates.joinToString("\n"))
    }

    private fun render() {
        root.removeAllViews()
        root.addView(
            hrow(
                btn("◀") { month = month.minusMonths(1); render() },
                weighted(label("${month.year}년 ${month.monthValue}월", 19f, true).apply { gravity = Gravity.CENTER }),
                btn("▶") { month = month.plusMonths(1); render() },
            )
        )

        val grid = GridLayout(this).apply { columnCount = 7 }
        fun cellParams() = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            height = dp(52)
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
        for ((i, w) in listOf("일", "월", "화", "수", "목", "금", "토").withIndex()) {
            val color = when (i) { 0 -> "#D64545"; 6 -> "#3A6EA5"; else -> "#444444" }
            grid.addView(label(w, 14f, true, color).apply { gravity = Gravity.CENTER }, cellParams().apply { height = dp(28) })
        }
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value % 7 // 일요일 = 0
        repeat(lead) { grid.addView(TextView(this), cellParams()) }
        val today = LocalDate.now()
        for (day in 1..month.lengthOfMonth()) {
            val d = month.atDay(day)
            val weekend = d.dayOfWeek.value >= 6
            val holiday = d in dates
            val cell = TextView(this).apply {
                text = day.toString()
                textSize = 17f
                gravity = Gravity.CENTER
                val fg = when {
                    holiday -> "#FFFFFF"
                    weekend -> "#AAAAAA"
                    else -> "#222222"
                }
                setTextColor(Color.parseColor(fg))
                val bg = if (holiday) Color.parseColor("#D64545") else Color.parseColor("#F2F2F2")
                background = if (d == today) rounded(bg, dp(8).toFloat(), dp(2), Color.parseColor("#2E7D5B"))
                else rounded(bg, dp(8).toFloat())
                if (!weekend) {
                    setOnClickListener {
                        if (holiday) dates.remove(d) else dates.add(d)
                        save()
                        render()
                    }
                }
            }
            grid.addView(cell, cellParams())
        }
        root.addView(grid)
        root.addView(label("평일 날짜를 누르면 휴일 지정/해제 (토·일은 자동으로 등교일 아님)", 12f, color = "#666666"))

        root.addView(section("기간으로 지정 (방학 등)"))
        root.addView(
            hrow(
                weighted(btn("기간 휴일 지정") { pickRange(true) }),
                weighted(btn("기간 해제") { pickRange(false) }),
            )
        )

        val inMonth = dates.filter { YearMonth.from(it) == month }
        root.addView(section("이번 달 휴일 (${inMonth.size}일)"))
        root.addView(
            label(
                if (inMonth.isEmpty()) "없음" else inMonth.joinToString(", ") { Att.short(it.toString()) },
                14f
            )
        )
    }

    private fun pickRange(add: Boolean) {
        val base = month.atDay(1)
        DatePickerDialog(this, { _, y1, m1, d1 ->
            val start = LocalDate.of(y1, m1 + 1, d1)
            Toast.makeText(this, "끝나는 날을 고르세요", Toast.LENGTH_SHORT).show()
            DatePickerDialog(this, { _, y2, m2, d2 ->
                val end = LocalDate.of(y2, m2 + 1, d2)
                val a = minOf(start, end)
                val b = maxOf(start, end)
                AlertDialog.Builder(this)
                    .setMessage("$a ~ $b 의 평일을 ${if (add) "휴일로 지정" else "휴일에서 해제"}할까요?")
                    .setPositiveButton("확인") { _, _ ->
                        var d = a
                        var guard = 0
                        while (!d.isAfter(b) && guard < 800) {
                            if (d.dayOfWeek.value <= 5) {
                                if (add) dates.add(d) else dates.remove(d)
                            }
                            d = d.plusDays(1)
                            guard++
                        }
                        save()
                        render()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }, start.year, start.monthValue - 1, start.dayOfMonth).apply { setTitle("끝나는 날") }.show()
        }, base.year, base.monthValue - 1, base.dayOfMonth).apply { setTitle("시작하는 날") }.show()
    }
}
