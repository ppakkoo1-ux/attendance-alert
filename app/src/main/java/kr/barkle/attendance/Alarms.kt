package kr.barkle.attendance

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 알림 예약.
 * 출석 확인 시각(슬롯 0~7)과 서류 안내 시각(슬롯 100)을 매일 반복으로 예약하고,
 * 알림이 울릴 때 그 순간의 기록을 보고 보낼지 결정한다.
 */
object Alarms {
    private const val ACTION = "kr.barkle.attendance.ALARM"
    private const val DOC_SLOT = 100
    private const val MAX_CHECK_SLOTS = 8

    private fun pending(c: Context, slot: Int, kind: String): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java)
            .setAction(ACTION)
            .putExtra("slot", slot)
            .putExtra("kind", kind)
        return PendingIntent.getBroadcast(
            c, slot, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun canExact(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun scheduleAll(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        for (i in 0 until MAX_CHECK_SLOTS) am.cancel(pending(c, i, "check"))
        Prefs.checkTimes(c).forEachIndexed { i, t -> schedule(c, am, pending(c, i, "check"), t) }
        schedule(c, am, pending(c, DOC_SLOT, "doc"), Prefs.docTime(c))
    }

    private fun schedule(c: Context, am: AlarmManager, p: PendingIntent, t: LocalTime) {
        val now = ZonedDateTime.now()
        var at = now.toLocalDate().atTime(t).atZone(ZoneId.systemDefault())
        if (!at.isAfter(now)) at = at.plusDays(1)
        val ms = at.toInstant().toEpochMilli()
        if (canExact(c)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind")
        val slot = intent.getIntExtra("slot", -1)
        val today = LocalDate.now()
        try {
            when (kind) {
                "check" -> Notifier.checkAlert(c, today, slot)
                "doc" -> if (SchoolCalendar.isSchoolDay(c, today)) Notifier.docAlert(c, today)
            }
        } finally {
            Alarms.scheduleAll(c)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Alarms.scheduleAll(c)
    }
}

object Notifier {
    const val CH_CHECK = "check"
    const val CH_DOC = "doc"
    const val CH_UPDATE = "update"

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_CHECK, "출석 확인 알림", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DOC, "출결서류 안내 알림", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_UPDATE, "앱 업데이트", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun checkAlert(c: Context, date: LocalDate, slot: Int) {
        val list = Db.get(c).unresolved(date.toString())
        if (list.isEmpty()) return
        val time = Prefs.checkTimes(c).getOrNull(slot)?.let { Att.fmtTime(it) } ?: ""
        val names = list.joinToString(", ") { "${it.num}번 ${it.name}" }
        post(
            c, 1000 + slot, CH_CHECK,
            "출석 확인 $time (${list.size}명)",
            "조회 때 없던 학생의 출석 여부를 확인하세요.\n$names"
        )
    }

    fun docAlert(c: Context, today: LocalDate) {
        val db = Db.get(c)
        val recs = db.pendingDocAlerts(today.toString())
        if (recs.isEmpty()) return
        val smap = db.studentMap()
        val lines = recs.joinToString("\n") { r ->
            val s = smap[r.studentId]
            "${Att.short(r.date)} ${s?.num ?: "?"}번 ${s?.name ?: "?"} ${r.label}"
        }
        post(
            c, 2000, CH_DOC,
            "출결서류 안내·제출받기 (${recs.size}건)",
            "조회 때 출결서류를 안내하고 제출받으세요.\n$lines"
        )
        db.markAlerted(recs.map { it.id })
    }

    fun post(c: Context, id: Int, channel: String, title: String, text: String, tap: Intent? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(c)
        val open = tap ?: Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            c, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text.lines().drop(1).joinToString(" ").ifBlank { text })
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
