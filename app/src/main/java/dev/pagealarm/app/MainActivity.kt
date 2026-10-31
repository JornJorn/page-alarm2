package dev.pagealarm.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var toggle: Button
    private lateinit var status: TextView
    private lateinit var setupHeader: TextView

    private class SetupItem(val button: Button, val needed: () -> Boolean)

    private val setupItems = mutableListOf<SetupItem>()
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Page to watch"
            textSize = 16f
        })

        urlInput = EditText(this).apply {
            hint = "https://example.com/page"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(Store.url(this@MainActivity))
        }
        root.addView(urlInput)

        toggle = Button(this).apply {
            setOnClickListener { onToggle() }
        }
        root.addView(toggle)

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Test alarm (check the volume)"
            setOnClickListener {
                startForegroundService(
                    Intent(this@MainActivity, MonitorService::class.java)
                        .setAction(MonitorService.ACTION_TEST)
                )
            }
        })

        setupHeader = TextView(this).apply {
            text = "\nFinish setup so the alarm can't be missed:"
            textSize = 15f
        }
        root.addView(setupHeader)

        addSetup("Allow notifications", { needsNotificationPermission() }) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        addSetup("Allow full-screen alarm", { needsFullScreenPermission() }) {
            open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
        }
        addSetup("Allow exact timing (every 5 min)", { needsExactAlarm() }) {
            open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        }
        addSetup("Turn off battery optimization", { needsBatteryExemption() }) {
            open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        }

        setContentView(ScrollView(this).apply { addView(root) })

        if (needsNotificationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ticker.run()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------------------------------------------------------------- actions

    private fun onToggle() {
        if (Store.monitoring(this)) {
            startService(
                Intent(this, MonitorService::class.java)
                    .setAction(MonitorService.ACTION_STOP_MONITORING)
            )
            Store.setMonitoring(this, false)
        } else {
            val url = normalizeUrl(urlInput.text.toString())
            if (url == null) {
                urlInput.error = "Enter a valid http(s) address"
                return
            }
            urlInput.setText(url)
            Store.setUrl(this, url)
            Store.clearBaseline(this)
            Store.setLastCheck(this, 0L, "")
            Store.setMonitoring(this, true)
            MonitorService.start(this)
        }
        refresh()
    }

    private fun normalizeUrl(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val uri = Uri.parse(s)
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrEmpty()) return null
        return s
    }

    /** Opens a system settings screen for this app, falling back to the app's own settings. */
    private fun open(action: String) {
        try {
            startActivity(Intent(action, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    // ------------------------------------------------------------------ setup

    private fun addSetup(label: String, needed: () -> Boolean, action: () -> Unit) {
        val b = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        (setupHeader.parent as LinearLayout).addView(b)
        setupItems.add(SetupItem(b, needed))
    }

    private fun needsNotificationPermission() =
        Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun needsFullScreenPermission() =
        Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun needsExactAlarm() =
        Build.VERSION.SDK_INT >= 31 &&
            !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun needsBatteryExemption() =
        !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // ---------------------------------------------------------------- display

    private fun refresh() {
        val on = Store.monitoring(this)
        toggle.text = if (on) "Stop monitoring" else "Start monitoring"
        urlInput.isEnabled = !on

        val last = Store.lastCheck(this)
        val whenText = if (last == 0L) {
            "not yet"
        } else {
            DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(last))
        }
        val result = Store.lastResult(this)

        status.text = buildString {
            append(if (on) "Monitoring, checking every 5 minutes\n" else "Not monitoring\n")
            append("Last check: ").append(whenText)
            if (result.isNotEmpty()) append("\n").append(result)
            if (Store.alarmActive(this@MainActivity)) append("\nALARM ACTIVE")
        }

        var anyNeeded = false
        for (item in setupItems) {
            val needed = item.needed()
            item.button.visibility = if (needed) View.VISIBLE else View.GONE
            if (needed) anyNeeded = true
        }
        setupHeader.visibility = if (anyNeeded) View.VISIBLE else View.GONE
    }
}
