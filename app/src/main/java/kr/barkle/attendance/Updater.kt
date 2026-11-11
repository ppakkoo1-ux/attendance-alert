package kr.barkle.attendance

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases의 최신 APK를 확인해 설치 화면까지 연다.
 * 릴리스 태그는 v{빌드번호}, 빌드번호 = versionCode.
 */
object Updater {

    private fun toast(a: Activity, s: String) = a.runOnUiThread { Toast.makeText(a, s, Toast.LENGTH_LONG).show() }

    fun check(a: Activity, manual: Boolean) {
        val repo = BuildConfig.GITHUB_REPO
        if (repo.isBlank()) {
            if (manual) toast(a, "업데이트 저장소가 설정되지 않은 빌드입니다")
            return
        }
        Thread {
            try {
                val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()
                val code = json.getString("tag_name").removePrefix("v").toIntOrNull() ?: 0
                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val o = assets.getJSONObject(i)
                    if (o.getString("name").endsWith(".apk")) {
                        apkUrl = o.getString("browser_download_url")
                        break
                    }
                }
                val url = apkUrl
                if (code > BuildConfig.VERSION_CODE && url != null) {
                    a.runOnUiThread {
                        if (a.isFinishing) return@runOnUiThread
                        AlertDialog.Builder(a)
                            .setTitle("새 버전 v$code")
                            .setMessage("현재 v${BuildConfig.VERSION_CODE}. 업데이트할까요?\n기록과 명단은 그대로 유지됩니다.")
                            .setPositiveButton("업데이트") { _, _ -> download(a, url) }
                            .setNegativeButton("나중에", null)
                            .show()
                    }
                } else if (manual) {
                    toast(a, "최신 버전입니다 (v${BuildConfig.VERSION_CODE})")
                }
            } catch (e: Exception) {
                if (manual) toast(a, "업데이트 확인 실패: ${e.message}")
            }
        }.start()
    }

    private fun download(a: Activity, url: String) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(a)
                .setTitle("설치 권한 필요")
                .setMessage("처음 한 번만 '이 출처 허용'을 켜 주세요. 켠 뒤 다시 업데이트를 누르면 됩니다.")
                .setPositiveButton("설정 열기") { _, _ ->
                    a.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))
                    )
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        toast(a, "새 버전을 내려받는 중…")
        Thread {
            try {
                val installer = a.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sid = installer.createSession(params)
                installer.openSession(sid).use { session ->
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                    conn.inputStream.use { input ->
                        session.openWrite("update.apk", 0, -1).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    conn.disconnect()
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(a, sid, Intent(a, InstallReceiver::class.java), flags)
                    session.commit(pi.intentSender)
                }
            } catch (e: Exception) {
                toast(a, "업데이트 실패: ${e.message}")
            }
        }.start()
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    c.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "오류 $status"
                Toast.makeText(c, "설치 실패: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
