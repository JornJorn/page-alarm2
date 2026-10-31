package dev.pagealarm.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Full-screen red alert shown over the lock screen while the siren is sounding. */
class AlarmActivity : Activity() {

    private lateinit var titleView: TextView
    private lateinit var urlView: TextView
    private lateinit var summaryView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!Store.alarmActive(this@AlarmActivity)) {
                finish() // dismissed from the notification
                return
            }
            render()
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.rgb(176, 0, 32))
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        titleView = TextView(this).apply {
            textSize = 36f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(titleView)

        urlView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(urlView)

        summaryView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
        }
        root.addView(
            ScrollView(this).apply { addView(summaryView) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        root.addView(Button(this).apply {
            text = "STOP ALARM"
            textSize = 24f
            setOnClickListener {
                startService(
                    Intent(this@AlarmActivity, MonitorService::class.java)
                        .setAction(MonitorService.ACTION_DISMISS_ALARM)
                )
                finish()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(96)
        ))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ticker.run()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun render() {
        titleView.text = if (Store.alarmIsTest(this)) "TEST ALARM" else "PAGE CHANGED"
        urlView.text = Store.url(this)
        summaryView.text = Store.alarmSummary(this)
    }
}
