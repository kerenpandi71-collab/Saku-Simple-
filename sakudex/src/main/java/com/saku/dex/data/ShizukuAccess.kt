package com.saku.dex.data

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuAccess {
    const val PERMISSION_REQUEST_CODE = 4102

    enum class State { NOT_RUNNING, UNSUPPORTED_API, PERMISSION_REQUIRED, GRANTED }

    fun state(): State {
        if (!Shizuku.pingBinder()) return State.NOT_RUNNING
        if (Shizuku.isPreV11()) return State.UNSUPPORTED_API
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            State.GRANTED
        } else {
            State.PERMISSION_REQUIRED
        }
    }

    fun requestPermission() {
        if (state() == State.PERMISSION_REQUIRED) {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    fun privilegeLabel(): String {
        if (state() != State.GRANTED) return "Belum terhubung"
        val uid = Shizuku.getUid()
        return when (uid) {
            0 -> "Terhubung • Root"
            2000 -> "Terhubung • ADB"
            else -> "Terhubung • UID $uid"
        }
    }
}
