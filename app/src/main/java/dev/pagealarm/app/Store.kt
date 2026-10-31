package dev.pagealarm.app

import android.content.Context

/** Tiny SharedPreferences wrapper holding all app state. */
object Store {
    private const val FILE = "pagealarm"

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Settings -----------------------------------------------------------

    fun url(c: Context): String = sp(c).getString("url", "") ?: ""
    fun setUrl(c: Context, v: String) = sp(c).edit().putString("url", v).apply()

    fun monitoring(c: Context): Boolean = sp(c).getBoolean("monitoring", false)
    fun setMonitoring(c: Context, v: Boolean) = sp(c).edit().putBoolean("monitoring", v).apply()

    // --- Page text ----------------------------------------------------------

    private fun encode(lines: List<String>) = lines.joinToString("\n")
    private fun decode(s: String?): List<String>? =
        when {
            s == null -> null
            s.isEmpty() -> emptyList()
            else -> s.split('\n')
        }

    /** Text the page is compared against, or null if no check has succeeded yet. */
    fun baseline(c: Context): List<String>? = decode(sp(c).getString("baseline", null))
    fun setBaseline(c: Context, lines: List<String>) =
        sp(c).edit().putString("baseline", encode(lines)).apply()
    fun clearBaseline(c: Context) = sp(c).edit().remove("baseline").apply()

    /** The changed text while an alarm is active; becomes the new baseline when dismissed. */
    fun pending(c: Context): List<String>? = decode(sp(c).getString("pending", null))
    fun setPending(c: Context, lines: List<String>) =
        sp(c).edit().putString("pending", encode(lines)).apply()
    fun clearPending(c: Context) = sp(c).edit().remove("pending").apply()

    // --- Status -------------------------------------------------------------

    fun lastCheck(c: Context): Long = sp(c).getLong("lastCheck", 0L)
    fun lastResult(c: Context): String = sp(c).getString("lastResult", "") ?: ""
    fun setLastCheck(c: Context, millis: Long, result: String) =
        sp(c).edit().putLong("lastCheck", millis).putString("lastResult", result).apply()

    // --- Alarm state --------------------------------------------------------

    fun alarmActive(c: Context): Boolean = sp(c).getBoolean("alarmActive", false)
    fun alarmSummary(c: Context): String = sp(c).getString("alarmSummary", "") ?: ""
    fun alarmIsTest(c: Context): Boolean = sp(c).getBoolean("alarmIsTest", false)

    fun setAlarm(c: Context, active: Boolean, summary: String, test: Boolean) =
        sp(c).edit()
            .putBoolean("alarmActive", active)
            .putString("alarmSummary", summary)
            .putBoolean("alarmIsTest", test)
            .apply()
}
