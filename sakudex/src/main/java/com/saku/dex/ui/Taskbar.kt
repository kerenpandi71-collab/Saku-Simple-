package com.saku.dex.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saku.dex.data.ShizukuAccess
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun Taskbar(
    modifier: Modifier = Modifier,
    onStartClick: () -> Unit,
    onDesktopClick: () -> Unit
) {
    val context = LocalContext.current
    var clock by remember { mutableStateOf(currentClock()) }
    var shizukuState by remember { mutableStateOf(ShizukuAccess.state()) }

    LaunchedEffect(Unit) {
        while (true) {
            clock = currentClock()
            shizukuState = ShizukuAccess.state()
            delay(1000)
        }
    }

    Row(
        modifier = modifier.fillMaxWidth().height(64.dp)
            .background(Color(0xD914202C), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            TaskbarButton("▦", onClick = onStartClick)
            TaskbarButton("⌂", onClick = onDesktopClick)
            TaskbarButton("⚙") {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                    Toast.makeText(context, "Pengaturan tidak dapat dibuka", Toast.LENGTH_SHORT).show()
                }
            }
        }
        Box(
            modifier = Modifier.weight(1f).height(58.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text("Saku DeX", color = Color(0xFFB7C6D5), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            TaskbarButton("S", compact = true) {
                when (shizukuState) {
                    ShizukuAccess.State.NOT_RUNNING ->
                        Toast.makeText(context, "Jalankan aplikasi Shizuku terlebih dahulu", Toast.LENGTH_LONG).show()
                    ShizukuAccess.State.UNSUPPORTED_API ->
                        Toast.makeText(context, "Versi API Shizuku tidak didukung", Toast.LENGTH_SHORT).show()
                    ShizukuAccess.State.PERMISSION_REQUIRED ->
                        runCatching { ShizukuAccess.requestPermission() }.onFailure {
                            Toast.makeText(context, "Tidak dapat meminta izin Shizuku", Toast.LENGTH_SHORT).show()
                        }
                    ShizukuAccess.State.GRANTED ->
                        Toast.makeText(context, ShizukuAccess.privilegeLabel(), Toast.LENGTH_SHORT).show()
                }
            }
            Text(
                text = clock,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 2.dp, end = 3.dp)
            )
        }
    }
}

@Composable
private fun TaskbarButton(
    icon: String,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.size(if (compact) 36.dp else 40.dp)
            .background(Color(0xFF303D4B), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(icon, color = Color.White, fontSize = if (compact) 17.sp else 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun currentClock(): String =
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
