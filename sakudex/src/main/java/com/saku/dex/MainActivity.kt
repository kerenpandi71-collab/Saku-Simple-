package com.saku.dex

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import com.farmerbb.taskbar.lib.Taskbar
import com.saku.dex.ui.DesktopScreen
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
                val message = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    "Izin Shizuku diberikan"
                } else {
                    "Izin Shizuku ditolak"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register Taskbar's SECONDARY_HOME activity. Android starts it on a
        // compatible external display when system Desktop Mode is enabled.
        Taskbar.setEnabled(applicationContext, true)

        Shizuku.addRequestPermissionResultListener(permissionListener)
        setContent {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme.copy(
                    background = Color(0xFF101820),
                    surface = Color(0xFF18212B)
                )
            ) {
                Surface { DesktopScreen() }
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    companion object {
        const val SHIZUKU_PERMISSION_REQUEST = 4102
    }
}
