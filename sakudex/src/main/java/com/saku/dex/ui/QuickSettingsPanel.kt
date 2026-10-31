package com.saku.dex.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun QuickSettingsPanel(expanded: Boolean, onDismiss: () -> Unit, context: Context) {
    val panelShape = RoundedCornerShape(22.dp)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.82f)
            .background(Brush.linearGradient(listOf(Color(0xFF243F61), Color(0xFF111D30))), panelShape)
            .padding(5.dp),
        shape = panelShape,
        containerColor = Color(0xFF17283D),
        tonalElevation = 12.dp,
        shadowElevation = 18.dp
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(Color(0xFF315D88), Color(0xFF203B5B))), RoundedCornerShape(17.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            Text("Quick Settings", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Kontrol cepat perangkat", color = Color(0xFFBED4EA), fontSize = 11.sp)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                QuickBadge("Wi-Fi")
                QuickBadge("Bluetooth")
                QuickBadge("Audio")
            }
        }
        PanelItem("◉   Internet / Wi-Fi", Settings.Panel.ACTION_INTERNET_CONNECTIVITY, context, onDismiss)
        PanelItem("ᛒ   Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, context, onDismiss)
        PanelItem("♫   Volume", Settings.Panel.ACTION_VOLUME, context, onDismiss)
        PanelItem("☼   Kecerahan layar", Settings.ACTION_DISPLAY_SETTINGS, context, onDismiss)
        PanelItem("⚙   Pengaturan perangkat", Settings.ACTION_SETTINGS, context, onDismiss)
    }
}

@Composable
private fun QuickBadge(label: String) {
    Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(Color(0xFF4778A8), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 5.dp))
}

@Composable
private fun PanelItem(label: String, action: String, context: Context, onDismiss: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = Color(0xFFEAF3FC), fontWeight = FontWeight.Medium) },
        onClick = { onDismiss(); openPanel(context, action) },
        colors = MenuDefaults.itemColors(textColor = Color(0xFFEAF3FC))
    )
}

private fun openPanel(context: Context, action: String) {
    runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, "Panel tidak didukung ROM ini", Toast.LENGTH_SHORT).show() }
}
