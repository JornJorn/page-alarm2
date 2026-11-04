package dev.pagealarm.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** Lists past alarms, newest first, with the lines that were added (+) and removed (-). */
class HistoryActivity : Activity() {

    private lateinit var list: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("Alarm history")

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(list) })
        render()
    }

    private fun render() {
        list.removeAllViews()
        val records = Store.history(this)

        if (records.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No alarms yet. Real page changes show up here after they happen."
                textSize = 16f
            })
            return
        }

        list.addView(Button(this).apply {
            text = "Clear history"
            setOnClickListener {
                AlertDialog.Builder(this@HistoryActivity)
                    .setMessage("Delete all ${records.size} entries?")
                    .setPositiveButton("Delete") { _, _ ->
                        Store.clearHistory(this@HistoryActivity)
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for (r in records) {
            list.addView(TextView(this).apply {
                text = fmt.format(Date(r.time))
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(20), 0, 0)
            })
            list.addView(TextView(this).apply {
                text = r.url
                textSize = 13f
            })
            list.addView(TextView(this).apply {
                text = r.summary
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, dp(6), 0, 0)
            })
        }
    }
}
