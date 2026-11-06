package com.keren.sakusimple

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small local-only ring log. Raw typed text and shell command payloads are redacted. */
internal object DiagnosticsLog {
    private const val PREFS = "saku_local_diagnostics"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 240
    private const val MAX_CHARS = 48_000
    private var lastFingerprint: String? = null
    private var lastLoggedAt = 0L
    private var suppressedRepeats = 0

    @Synchronized
    fun append(context: Context, event: String) {
        val safe = sanitize(event)
        if (safe.isBlank()) return
        val now = System.currentTimeMillis()
        if (safe == lastFingerprint && now - lastLoggedAt < 2_000L) {
            suppressedRepeats++
            return
        }
        val line = if (safe == lastFingerprint && suppressedRepeats > 0) {
            "$safe • diulang $suppressedRepeats kali"
        } else safe
        lastFingerprint = safe
        lastLoggedAt = now
        suppressedRepeats = 0
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_EVENTS, "").orEmpty()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val lines = ((previous.lineSequence().filter { it.isNotBlank() } + "$stamp • $line")
            .toList().takeLast(MAX_EVENTS)).toMutableList()
        var joined = lines.joinToString("\n")
        while (joined.length > MAX_CHARS && lines.size > 1) {
            lines.removeAt(0)
            joined = lines.joinToString("\n")
        }
        prefs.edit().putString(KEY_EVENTS, joined.takeLast(MAX_CHARS)).apply()
    }

    fun read(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_EVENTS, "").orEmpty()

    fun clear(context: Context) {
        lastFingerprint = null
        lastLoggedAt = 0L
        suppressedRepeats = 0
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_EVENTS).apply()
    }

    private fun sanitize(input: String): String = input
        .replace(Regex("(?i)cmd=.*"), "cmd=<disamarkan>")
        .replace(Regex("(?i)(input\\s+-d\\s+\\d+\\s+text\\s+)'[^']*'")) { it.groupValues[1] + "'<teks-disamarkan>'" }
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .take(700)
}
