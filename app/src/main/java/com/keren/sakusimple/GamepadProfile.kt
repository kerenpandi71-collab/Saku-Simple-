package com.keren.sakusimple

import android.content.Context

/** Normalized virtual-display coordinates, so a profile survives resolution changes. */
data class ControlPoint(val x: Float, val y: Float) {
    fun bounded(): ControlPoint = ControlPoint(x.coerceIn(0.02f, 0.98f), y.coerceIn(0.02f, 0.98f))
}

enum class GamepadControl(val label: String, val pointerId: Int) {
    MOVE("Analog kiri", 0),
    ATTACK("Serang", 1),
    SKILL_1("Skill 1", 2),
    SKILL_2("Skill 2", 3),
    SKILL_3("Ultimate / Skill 3", 4),
    RECALL("Recall", 5),
    BATTLE_SPELL("Battle Spell", 6),
    REGEN("Regen", 7)
}

data class GamepadProfile(
    val movement: ControlPoint = ControlPoint(0.145f, 0.755f),
    val attack: ControlPoint = ControlPoint(0.925f, 0.835f),
    val skill1: ControlPoint = ControlPoint(0.805f, 0.700f),
    val skill2: ControlPoint = ControlPoint(0.875f, 0.610f),
    val skill3: ControlPoint = ControlPoint(0.755f, 0.590f),
    val recall: ControlPoint = ControlPoint(0.955f, 0.575f),
    val battleSpell: ControlPoint = ControlPoint(0.945f, 0.485f),
    val regen: ControlPoint = ControlPoint(0.865f, 0.465f)
) {
    fun pointFor(control: GamepadControl): ControlPoint = when (control) {
        GamepadControl.MOVE -> movement
        GamepadControl.ATTACK -> attack
        GamepadControl.SKILL_1 -> skill1
        GamepadControl.SKILL_2 -> skill2
        GamepadControl.SKILL_3 -> skill3
        GamepadControl.RECALL -> recall
        GamepadControl.BATTLE_SPELL -> battleSpell
        GamepadControl.REGEN -> regen
    }

    fun withPoint(control: GamepadControl, point: ControlPoint): GamepadProfile {
        val safe = point.bounded()
        return when (control) {
            GamepadControl.MOVE -> copy(movement = safe)
            GamepadControl.ATTACK -> copy(attack = safe)
            GamepadControl.SKILL_1 -> copy(skill1 = safe)
            GamepadControl.SKILL_2 -> copy(skill2 = safe)
            GamepadControl.SKILL_3 -> copy(skill3 = safe)
            GamepadControl.RECALL -> copy(recall = safe)
            GamepadControl.BATTLE_SPELL -> copy(battleSpell = safe)
            GamepadControl.REGEN -> copy(regen = safe)
        }
    }

    fun save(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        GamepadControl.entries.forEach { control ->
            val point = pointFor(control).bounded()
            editor.putFloat(key(packageName, control, "x"), point.x)
            editor.putFloat(key(packageName, control, "y"), point.y)
        }
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "saku_gamepad_profiles"

        fun load(context: Context, packageName: String): GamepadProfile {
            if (packageName.isBlank()) return GamepadProfile()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var profile = GamepadProfile()
            GamepadControl.entries.forEach { control ->
                val fallback = profile.pointFor(control)
                val point = ControlPoint(
                    prefs.getFloat(key(packageName, control, "x"), fallback.x),
                    prefs.getFloat(key(packageName, control, "y"), fallback.y)
                ).bounded()
                profile = profile.withPoint(control, point)
            }
            return profile
        }

        private fun key(packageName: String, control: GamepadControl, axis: String): String =
            "$packageName.${control.name.lowercase()}.$axis"
    }
}
