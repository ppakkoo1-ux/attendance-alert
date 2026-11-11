package kr.barkle.attendance

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import java.time.LocalDate

class MainActivity : Activity() {

    private var date: LocalDate = LocalDate.now()
    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout
    private val db by lazy { Db.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("date")?.let { date = LocalDate.parse(it) }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(32))
        }
        scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        Notifier.ensureChannels(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (!Alarms.canExact(this) && Build.VERSION.SDK_INT >= 31) {
            AlertDialog.Builder(this)
                .setTitle("정확한 알림 시각 허용")
                .setMessage("정해진 시각에 알림이 울리려면 '알람 및 리마인더' 권한을 허용해 주세요.")
                .setPositiveButton("설정 열기") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("나중에", null)
                .show()
        }
        Alarms.scheduleAll(this)
        Updater.check(this, manual = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("date", date.toString())
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun refresh() = root.post { render() }

    private fun render() {
        val sy = scroll.scrollY
        root.removeAllViews()
        val ds = date.toString()

        // 날짜 이동
        val dateBtn = btn(Att.pretty(date)) { pickDate() }.apply { textSize = 17f }
        root.addView(
            hrow(
                btn("◀") { date = date.minusDays(1); render() },
                weighted(dateBtn),
                btn("▶") { date = date.plusDays(1); render() },
            )
        )
        root.addView(
            hrow(
                weighted(btn("오늘") { date = LocalDate.now(); render() }),
                weighted(btn("통계") { startActivity(Intent(this, StatsActivity::class.java)) }),
                weighted(btn("설정") { startActivity(Intent(this, SettingsActivity::class.java)) }),
            )
        )
        if (!SchoolCalendar.isSchoolDay(this, date)) {
            root.addView(label("※ 등교일이 아닌 날입니다 (주말·휴일)", 13f, color = "#B00020"))
        }

        val students = db.students()
        if (students.isEmpty()) {
            root.addView(section("학급 명단이 없습니다"))
            root.addView(label("설정에서 학급 명단을 먼저 입력하세요."))
            return
        }

        val daily = db.daily(ds)
        val recs = db.records(ds)
        val recBySid = recs.groupBy { it.studentId }
        val smap = db.studentMap()

        // ---------- 조회 ----------
        root.addView(section("조회 — 없는 학생을 누르세요 (다시 누르면 취소)"))
        root.addView(label("길게 누르면 결석·지각·조퇴·결과를 입력합니다", 12f, color = "#666666"))

        val grid = GridLayout(this).apply { columnCount = 5 }
        for (s in students) {
            val d = daily[s.id]
            val r = recBySid[s.id].orEmpty()
            val (bg, fg) = when {
                r.any { it.category == "결석" } -> "#6A4C93" to "#FFFFFF"
                d != null && d.missing && d.arrived ->"#2E7D5B" to "#FFFFFF"
                d?.missing == true -> "#D64545" to "#FFFFFF"
                r.isNotEmpty() -> "#E89B2C" to "#FFFFFF"
                else -> "#EEEEEE" to "#222222"
            }
            val b = Button(this).apply {
                text = "${s.num}\n${s.name}"
                isAllCaps = false
                textSize = 14f
                setTextColor(Color.parseColor(fg))
                background = rounded(Color.parseColor(bg), dp(10).toFloat())
                setPadding(dp(2), dp(4), dp(2), dp(4))
                setOnClickListener {
                    if (d?.missing == true) {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage("${s.num}번 ${s.name} 학생을 '조회 때 없음'에서 취소할까요?")
                            .setPositiveButton("취소하기") { _, _ -> db.setMissing(ds, s.id, false); render() }
                            .setNegativeButton("그대로 두기", null)
                            .show()
                    } else {
                        db.setMissing(ds, s.id, true)
                        render()
                    }
                }
                setOnLongClickListener {
                    recordDialog(s, ds, d?.missing == true)
                    true
                }
            }
            val p = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = dp(64)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            grid.addView(b, p)
        }
        root.addView(grid)
        root.addView(
            label("빨강: 조회 때 없음 · 초록: 출석 확인 · 보라: 결석 · 주황: 그 외 기록", 12f, color = "#666666")
        )

        // ---------- 조회 때 없던 학생 ----------
        val missing = students.filter { daily[it.id]?.missing == true }
        if (missing.isNotEmpty()) {
            val unresolved = db.unresolved(ds)
            root.addView(section("조회 때 없던 학생 (미확인 ${unresolved.size}명)"))
            for (s in missing) {
                val d = daily.getValue(s.id)
                val absentRecs = recBySid[s.id].orEmpty().filter { it.category == "결석" }
                val cb = CheckBox(this).apply {
                    text = "${s.num}번 ${s.name}  출석"
                    textSize = 17f
                    isChecked = d.arrived
                    isEnabled = absentRecs.isEmpty()
                    setOnCheckedChangeListener { _, checked ->
                        db.setArrived(ds, s.id, checked)
                        refresh()
                    }
                }
                val info = label(absentRecs.joinToString(", ") { it.label }, 14f, color = "#6A4C93")
                root.addView(hrow(weighted(cb), info, btn("사유") { recordDialog(s, ds, true) }))
            }
            val times = Prefs.checkTimes(this).joinToString(", ") { Att.fmtTime(it) }
            root.addView(
                label(
                    if (unresolved.isEmpty()) "모두 확인됨 — 남은 출석 확인 알림은 울리지 않습니다."
                    else "미확인 학생이 남아 있으면 $times 에 알림이 옵니다.",
                    13f, color = "#666666"
                )
            )
        }

        // ---------- 이날 출결 기록 ----------
        root.addView(section("이날 출결 기록"))
        if (recs.isEmpty()) {
            root.addView(label("없음", 14f, color = "#666666"))
        } else {
            for (r in recs) {
                val s = smap[r.studentId]
                val memo = if (r.memo.isNotBlank()) "  ${r.memo}" else ""
                val per = if (r.periods.isNotBlank() && r.category != "결석") "\n${r.periods}" else ""
                val t = label("${s?.num ?: "?"}번 ${s?.name ?: "?"}  ${r.label}$memo$per", 16f)
                val row = hrow(weighted(t)).apply { setPadding(0, dp(6), 0, dp(6)) }
                if (r.needsDoc) {
                    row.addView(CheckBox(this).apply {
                        text = "서류 제출"
                        isChecked = r.docSubmitted
                        setOnCheckedChangeListener { _, checked -> db.setDocSubmitted(r.id, checked) }
                    })
                }
                val del = {
                    AlertDialog.Builder(this)
                        .setMessage("${s?.name ?: ""} ${r.label} 기록을 삭제할까요?")
                        .setPositiveButton("삭제") { _, _ -> db.deleteRecord(r.id); render() }
                        .setNegativeButton("취소", null)
                        .show()
                    true
                }
                row.setOnLongClickListener { del() }
                t.setOnLongClickListener { del() }
                root.addView(row)
            }
            root.addView(label("기록을 길게 누르면 삭제합니다", 12f, color = "#666666"))
        }

        scroll.post { scroll.scrollTo(0, sy) }
    }

    private fun pickDate() {
        DatePickerDialog(
            this,
            { _, y, m, d -> date = LocalDate.of(y, m + 1, d); render() },
            date.year, date.monthValue - 1, date.dayOfMonth
        ).show()
    }

    private fun recordDialog(s: Student, ds: String, wasMissing: Boolean) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(label("출결", 14f, true))
        val catGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        Att.CATEGORIES.forEachIndexed { i, c ->
            catGroup.addView(RadioButton(this).apply { text = c; id = 100 + i; textSize = 16f })
        }
        catGroup.check(if (wasMissing) 100 else 102)
        box.addView(catGroup)

        box.addView(label("종류", 14f, true).apply { setPadding(0, dp(10), 0, 0) })
        val reasonGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        Att.REASONS.forEachIndexed { i, r ->
            reasonGroup.addView(RadioButton(this).apply { text = r; id = 200 + i; textSize = 16f })
        }
        box.addView(reasonGroup)

        // ---------- 교시 ----------
        var n = Prefs.periodsOn(this, LocalDate.parse(ds))
        var fromSp: Spinner? = null
        var toSp: Spinner? = null

        fun spinner(opts: List<Int>, sel: Int, name: (Int) -> String = { Att.periodName(it) }) = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, opts.map(name)
            )
            setSelection(opts.indexOf(sel).coerceAtLeast(0))
            tag = opts
        }

        fun value(sp: Spinner?): Int {
            if (sp == null) return 0
            val opts = sp.tag as? List<*> ?: return 0
            return opts[sp.selectedItemPosition] as Int
        }

        box.addView(label("교시", 14f, true).apply { setPadding(0, dp(10), 0, 0) })
        val nOpts = (4..9).toList()
        val nSp = spinner(nOpts, n) { "이날 ${it}교시까지 수업" }
        box.addView(nSp)
        val periodBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(periodBox)

        fun rebuild() {
            periodBox.removeAllViews()
            fromSp = null
            toSp = null
            when (Att.CATEGORIES.getOrNull(catGroup.checkedRadioButtonId - 100)) {
                "결석" -> periodBox.addView(
                    label("결시교시: " + Att.periodsText("결석", n, 0, 0), 13f, color = "#666666")
                )
                "지각" -> {
                    val sp = spinner(listOf(Att.HOMEROOM) + (1..n).toList(), 1)
                    toSp = sp
                    periodBox.addView(hrow(label("몇 교시까지 빠졌나  ", 15f), weighted(sp)))
                }
                "조퇴" -> {
                    val sp = spinner((1..n).toList() + Att.END, minOf(5, n))
                    fromSp = sp
                    periodBox.addView(hrow(label("몇 교시부터 조퇴  ", 15f), weighted(sp)))
                }
                "결과" -> {
                    val a = spinner((1..n).toList(), 1)
                    val b = spinner(listOf(-1) + (1..n).toList(), -1) { if (it < 0) "그 한 시간만" else Att.periodName(it) }
                    fromSp = a
                    toSp = b
                    periodBox.addView(hrow(label("시작 교시  ", 15f), weighted(a)))
                    periodBox.addView(hrow(label("끝 교시  ", 15f), weighted(b)))
                }
            }
        }
        rebuild()
        catGroup.setOnCheckedChangeListener { _, _ -> rebuild() }
        nSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val v = nOpts[position]
                if (v != n) {
                    n = v
                    rebuild()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val memo = EditText(this).apply {
            hint = "사유 (예: 감기, 진학설명회 참석)"
            isSingleLine = true
            gravity = Gravity.START
        }
        box.addView(memo)

        val dlg = AlertDialog.Builder(this)
            .setTitle("${s.num}번 ${s.name} 출결 입력")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ci = catGroup.checkedRadioButtonId - 100
                val ri = reasonGroup.checkedRadioButtonId - 200
                if (ci !in Att.CATEGORIES.indices || ri !in Att.REASONS.indices) {
                    Toast.makeText(this, "출결과 종류를 선택하세요", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val cat = Att.CATEGORIES[ci]
                var from = 0
                var to = 0
                when (cat) {
                    "지각" -> to = value(toSp)
                    "조퇴" -> from = value(fromSp)
                    "결과" -> {
                        from = value(fromSp)
                        to = value(toSp).let { if (it < 0) from else it }
                        if (to < from) {
                            Toast.makeText(this, "끝 교시가 시작 교시보다 앞입니다", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                    }
                }
                db.addRecord(
                    ds, s.id, cat, Att.REASONS[ri], memo.text.toString().trim(),
                    Att.periodsText(cat, n, from, to)
                )
                dlg.dismiss()
                render()
            }
        }
        dlg.show()
    }
}
