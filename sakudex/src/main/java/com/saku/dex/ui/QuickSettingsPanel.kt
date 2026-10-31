package com.saku.dex.ui
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
@Composable
internal fun QuickSettingsPanel(expanded: Boolean, onDismiss: () -> Unit, context: Context) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.78f), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("Quick Settings", color = Color(0xFF263746), fontSize = 17.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            Text("Kontrol melalui panel sistem Android", color = Color(0xFF607080), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        }
        PanelItem("Internet / Wi-Fi", Settings.Panel.ACTION_INTERNET_CONNECTIVITY, context, onDismiss)
        PanelItem("Bluetooth", Settings.Panel.ACTION_BLUETOOTH, context, onDismiss)
        PanelItem("Volume", Settings.Panel.ACTION_VOLUME, context, onDismiss)
        PanelItem("Kecerahan layar", Settings.Panel.ACTION_BRIGHTNESS, context, onDismiss)
        DropdownMenuItem(text = { Text("Pengaturan perangkat") }, onClick = {
            onDismiss(); openPanel(context, Settings.ACTION_SETTINGS)
        })
    }
}
@Composable
private fun PanelItem(label: String, action: String, context: Context, onDismiss: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = { onDismiss(); openPanel(context, action) })
}
private fun openPanel(context: Context, action: String) {
    runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, "Panel tidak didukung ROM ini", Toast.LENGTH_SHORT).show() }
}
