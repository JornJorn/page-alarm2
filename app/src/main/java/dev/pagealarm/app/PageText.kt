package dev.pagealarm.app

import org.jsoup.Jsoup

/**
 * Downloads a page and reduces it to its visible text, one entry per visual line.
 * Scripts, styles, markup, attributes and comments are ignored, so only changes
 * to the text a person would read trigger the alarm.
 */
object PageText {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36 PageAlarm/1.0"

    private const val BLOCK_TAGS =
        "p,div,li,tr,td,th,h1,h2,h3,h4,h5,h6,section,article,header,footer,nav," +
            "table,ul,ol,dl,dd,dt,blockquote,pre,form,main,aside,figure,figcaption"

    /** Fetches [url] and returns its visible text lines. Throws on network/HTTP errors. */
    fun fetch(url: String): List<String> {
        val doc = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(20_000)
            .followRedirects(true)
            .maxBodySize(3_000_000)
            .get()
        return extract(doc)
    }

    private fun extract(doc: org.jsoup.nodes.Document): List<String> {
        doc.select("script,style,noscript,template,head,svg,iframe,canvas").remove()

        // Mark line breaks with a literal backslash-n so they survive text().
        doc.select("br").append("\\n")
        doc.select(BLOCK_TAGS).prepend("\\n")
        doc.select(BLOCK_TAGS).append("\\n")

        val text = (doc.body()?.text() ?: "").replace("\\n", "\n")

        return text.lines()
            .map { it.replace(' ', ' ').replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
    }

    /** Short human-readable description of what changed between two versions. */
    fun describeChange(old: List<String>, fresh: List<String>): String {
        val oldSet = old.toHashSet()
        val freshSet = fresh.toHashSet()
        val added = fresh.filter { it !in oldSet }.distinct()
        val removed = old.filter { it !in freshSet }.distinct()

        val sb = StringBuilder()
        if (added.isEmpty() && removed.isEmpty()) {
            sb.append("The text was rearranged.")
        }
        added.take(8).forEach { sb.append("+ ").append(it.take(160)).append('\n') }
        if (added.size > 8) sb.append("+ ... ").append(added.size - 8).append(" more\n")
        removed.take(8).forEach { sb.append("- ").append(it.take(160)).append('\n') }
        if (removed.size > 8) sb.append("- ... ").append(removed.size - 8).append(" more\n")
        return sb.toString().trim()
    }
}
