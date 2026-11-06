package com.keren.sakusimple

import android.content.Context
import org.json.JSONObject
import java.util.Date
import java.util.Locale

/** Versioned, app-scoped backup. Never imports arbitrary preference files or values. */
internal object SettingsBackup {
    const val MIME_TYPE = "application/json"
    const val FORMAT = "com.keren.sakugamepad.settings"
    private const val SCHEMA_VERSION = 1
    private val stores = setOf("standalone_gamepad_ui", "saku_gamepad_overlay_layout", "saku_gamepad_profiles")
    private val packagePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private val overlayKey = Regex("^([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)\\.(move|attack|skill_1|skill_2|skill_3|recall|battle_spell|regen)\\.(x|y|scale)$")
    private val profileKey = Regex("^([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)\\.(move|attack|skill_1|skill_2|skill_3|recall|battle_spell|regen)\\.(x|y)$")

    class ImportPlan internal constructor(
        private val values: Map<String, Map<String, Any>>,
        val summary: String
    ) {
        fun apply(context: Context) {
            values.forEach { (store, entries) ->
                val editor = context.getSharedPreferences(store, Context.MODE_PRIVATE).edit()
                val appliedEntries = entries.toMutableMap()
                if (store == "standalone_gamepad_ui") {
                    val prefix = if (entries["portrait_mode"] == true) "portrait" else "landscape"
                    val legacyToMode = mapOf(
                        "display_width" to "${prefix}_display_width",
                        "display_height" to "${prefix}_display_height",
                        "display_density_dpi" to "${prefix}_display_density_dpi"
                    )
                    legacyToMode.forEach { (legacy, current) ->
                        val value = entries[legacy]
                        if (value != null && current !in entries) appliedEntries[current] = value
                    }
                }
                appliedEntries.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                        else -> error("Unsupported preference type")
                    }
                }
                check(editor.commit()) { "Gagal menyimpan bagian $store" }
            }
        }
    }

    fun export(context: Context): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("appId", "com.keren.sakugamepad")
            .put("exportedAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
        val allStores = JSONObject()
        stores.forEach { name ->
            val entries = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (value == null || !isValid(name, key, value)) return@forEach
                entries.put(key, encode(value))
            }
            allStores.put(name, entries)
        }
        root.put("preferences", allStores)
        return root.toString(2)
    }

    fun parse(json: String): ImportPlan {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "File backup lebih besar dari 2 MiB." }
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "Format file bukan backup Saku Gamepad." }
        require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION) { "Versi backup belum didukung." }
        require(root.optString("appId") == "com.keren.sakugamepad") { "Backup berasal dari aplikasi/flavor yang berbeda." }
        val rawStores = root.optJSONObject("preferences") ?: error("Bagian preferences tidak ditemukan.")
        val result = linkedMapOf<String, Map<String, Any>>()
        var settingCount = 0
        var gamePackages = linkedSetOf<String>()
        val names = rawStores.keys()
        while (names.hasNext()) {
            val store = names.next()
            require(store in stores) { "Preference store tidak dikenal: $store" }
            val rawEntries = rawStores.optJSONObject(store) ?: error("Data $store tidak valid.")
            val entries = linkedMapOf<String, Any>()
            val keys = rawEntries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val decoded = decode(rawEntries.getJSONObject(key))
                require(isValid(store, key, decoded)) { "Nilai setting tidak valid: $store / $key" }
                entries[key] = decoded
                settingCount++
                when (store) {
                    "saku_gamepad_profiles" -> profileKey.matchEntire(key)?.groupValues?.get(1)?.let(gamePackages::add)
                    "saku_gamepad_overlay_layout" -> overlayKey.matchEntire(key)?.groupValues?.get(1)?.let(gamePackages::add)
                }
            }
            result[store] = entries
        }
        require(settingCount > 0) { "Backup tidak berisi pengaturan yang dikenali." }
        val summary = buildString {
            append("Ditemukan $settingCount nilai pengaturan.")
            if (gamePackages.isNotEmpty()) append("\nProfil/tombol game: ${gamePackages.size} aplikasi.")
            append("\nPengaturan yang tidak tercantum di file akan dibiarkan apa adanya.")
            append("\n\nPastikan file backup ini berasal dari perangkatmu sebelum melanjutkan.")
        }
        return ImportPlan(result, summary)
    }

    private fun encode(value: Any): JSONObject = when (value) {
        is Boolean -> JSONObject().put("type", "boolean").put("value", value)
        is Int -> JSONObject().put("type", "int").put("value", value)
        is Long -> JSONObject().put("type", "long").put("value", value)
        is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
        is String -> JSONObject().put("type", "string").put("value", value)
        else -> error("Unsupported preference type: ${value.javaClass.simpleName}")
    }

    private fun decode(entry: JSONObject): Any = when (entry.optString("type")) {
        "boolean" -> entry.getBoolean("value")
        "int" -> entry.getInt("value")
        "long" -> entry.getLong("value")
        "float" -> entry.getDouble("value").toFloat().also { require(it.isFinite()) }
        "string" -> entry.getString("value").take(512)
        else -> error("Jenis nilai backup tidak didukung.")
    }

    private fun isValid(store: String, key: String, value: Any): Boolean {
        if (store == "standalone_gamepad_ui") return when (key) {
            "display_width", "landscape_display_width", "portrait_display_width" -> value is Int && value in 320..2560
            "display_height", "landscape_display_height", "portrait_display_height" -> value is Int && value in 320..2560
            "display_density_dpi", "landscape_display_density_dpi", "portrait_display_density_dpi" -> value is Int && value in 120..640
            "portrait_mode", "portrait_keyboard_visible", "portrait_trackpad_visible", "gamepad_visible", "trackpad_visible", "mouse_trackpad_mode" -> value is Boolean
            "display_scale_x", "display_scale_y" -> value is Float && value.isFinite() && value in 0.5f..1.5f
            "cursor_sensitivity" -> value is Float && value.isFinite() && value in 0.2f..1.5f
            "selected_game_package" -> value is String && (value.isEmpty() || packagePattern.matches(value))
            else -> false
        }
        if (store == "saku_gamepad_overlay_layout") {
            val match = overlayKey.matchEntire(key) ?: return false
            if (value !is Float || !value.isFinite()) return false
            return when (match.groupValues[3]) {
                "scale" -> value in 0.6f..1.6f
                else -> value in 0.02f..0.98f
            }
        }
        if (store == "saku_gamepad_profiles") {
            if (profileKey.matchEntire(key) == null) return false
            return value is Float && value.isFinite() && value in 0.02f..0.98f
        }
        return false
    }

    private const val MAX_BACKUP_BYTES = 2 * 1024 * 1024
}
