package kr.barkle.attendance

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

class SettingsActivity : Activity() {

    private val db by lazy { Db.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        fun multiline(text: String, hint: String, lines: Int) = EditText(this).apply {
            setText(text)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
            textSize = 16f
        }

        // 학급 명단
        root.addView(section("학급 명단"))
        root.addView(label("한 줄에 한 명: 번호 이름 (예: 1 김민준). 번호를 빼면 순서대로 번호가 붙습니다.", 13f, color = "#666666"))
        val roster = multiline(
            db.students().joinToString("\n") { "${it.num} ${it.name}" },
            "1 김민준\n2 이서연\n…", 8
        )
        root.addView(roster)

        // 알림 시각
        root.addView(section("출석 확인 알림 시각"))
        root.addView(label("조회 때 없던 학생이 미확인으로 남아 있을 때 울립니다. 쉼표로 구분.", 13f, color = "#666666"))
        val checkTimes = EditText(this).apply { setText(Prefs.checkTimesText(this@SettingsActivity)); textSize = 16f }
        root.addView(checkTimes)

        root.addView(section("출결서류 안내 알림 시각"))
        root.addView(label("기록한 날의 다음 등교일 이 시각에 울립니다.", 13f, color = "#666666"))
        val docTime = EditText(this).apply { setText(Prefs.docTimeText(this@SettingsActivity)); textSize = 16f }
        root.addView(docTime)

        // 학급 정보 (PDF 머리글)
        root.addView(section("학급 정보"))
        root.addView(label("출결 현황 PDF 머리글에 들어갑니다.", 13f, color = "#666666"))
        fun field(hint: String, value: String, number: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 16f
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
        }
        val school = field("학교명 (예: 항동중학교)", Prefs.schoolName(this))
        val grade = field("학년", Prefs.grade(this), true)
        val classNo = field("반", Prefs.classNo(this), true)
        root.addView(school)
        root.addView(hrow(weighted(grade), label(" 학년   "), weighted(classNo), label(" 반")))

        // 교시 수
        root.addView(section("요일별 교시 수 (월, 화, 수, 목, 금)"))
        root.addView(label("결석·조퇴의 결시교시를 만들 때 씁니다. 출결 입력 때 그날만 바꿀 수 있습니다.", 13f, color = "#666666"))
        val periods = EditText(this).apply { setText(Prefs.periodsText(this@SettingsActivity)); textSize = 16f }
        root.addView(periods)

        // 휴일
        root.addView(section("휴일·방학 (토·일은 자동 제외)"))
        root.addView(btn("달력에서 휴일·방학 지정") {
            startActivity(Intent(this, HolidayActivity::class.java))
        })

        root.addView(btn("저장") {
            val parsed = parseRoster(roster.text.toString())
            val times = checkTimes.text.toString().split(",", " ").mapNotNull { Att.parseTime(it) }
            val doc = Att.parseTime(docTime.text.toString())
            val pNums = periods.text.toString().split(",", " ").mapNotNull { it.trim().toIntOrNull() }
            when {
                times.isEmpty() -> toast("출석 확인 시각을 08:50 형식으로 입력하세요")
                doc == null -> toast("서류 안내 시각을 08:35 형식으로 입력하세요")
                pNums.size != 5 || pNums.any { it !in 1..10 } -> toast("교시 수를 월~금 5개 숫자로 입력하세요 (예: 6, 7, 6, 7, 6)")
                else -> {
                    db.saveRoster(parsed)
                    Prefs.save(
                        this,
                        times.distinct().sorted().joinToString(", ") { Att.fmtTime(it) },
                        Att.fmtTime(doc),
                        school.text.toString().trim(),
                        grade.text.toString().trim(),
                        classNo.text.toString().trim(),
                        pNums.joinToString(", ")
                    )
                    Alarms.scheduleAll(this)
                    toast("저장했습니다 (학생 ${parsed.size}명)")
                    finish()
                }
            }
        }.apply { textSize = 17f })

        // 기타
        root.addView(section("알림이 잘 오게 하려면"))
        root.addView(btn("테스트 알림 보내기") {
            Notifier.post(this, 9999, Notifier.CH_CHECK, "테스트 알림", "알림이 정상적으로 옵니다.")
        })
        root.addView(btn("배터리 최적화 설정 열기") {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                toast("설정 화면을 열 수 없습니다")
            }
        })
        root.addView(label("배터리 설정에서 이 앱을 '제한 없음'으로 두면 알림이 늦지 않습니다.", 13f, color = "#666666"))

        root.addView(section("앱 정보"))
        root.addView(label("버전 v${BuildConfig.VERSION_CODE}", 14f))
        root.addView(btn("업데이트 확인") { Updater.check(this, manual = true) })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun parseRoster(text: String): List<Pair<Int, String>> {
        val re = Regex("^\\s*(\\d+)\\s*[.)번]?\\s*(.+?)\\s*$")
        val out = mutableListOf<Pair<Int, String>>()
        var auto = 1
        for (line in text.lines()) {
            if (line.isBlank()) continue
            val m = re.find(line)
            if (m != null && m.groupValues[2].isNotBlank()) {
                val n = m.groupValues[1].toInt()
                out.add(n to m.groupValues[2])
                auto = n + 1
            } else {
                out.add(auto to line.trim())
                auto++
            }
        }
        return out
    }
}
