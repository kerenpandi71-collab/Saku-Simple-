package com.keren.sakusimple

import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF07182D)
private val TextMain = Color(0xFFF3F8FF)
private val TextSoft = Color(0xFFBBD0E8)
private val Panel = Color(0xAA102C4B)
private val Stroke = Color(0x553EBEFF)
private val Glow = Color(0xFF69DDF5)

data class AppEntry(val label: String, val packageName: String, val icon: Drawable)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        setContent {
            MaterialTheme {
                SakuDesktop(
                    loadApps = { loadLaunchableApps() },
                    openApp = { launchPackage(it) },
                    openSettings = { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) },
                    deviceInfo = { readDeviceInfo() }
                )
            }
        }
    }

    private fun loadLaunchableApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                AppEntry(info.loadLabel(packageManager).toString(), pkg, info.loadIcon(packageManager))
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun launchPackage(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun readDeviceInfo(): DeviceInfo {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val totalRam = memory.totalMem / (1024L * 1024L * 1024L)
        val usedRam = ((memory.totalMem - memory.availMem) / (1024L * 1024L * 10L)).toDouble() / 100.0
        val stat = StatFs(filesDir.absolutePath)
        val total = stat.totalBytes / (1024L * 1024L * 1024L)
        val free = stat.availableBytes / (1024L * 1024L * 1024L)
        return DeviceInfo(batteryPct, usedRam, totalRam, total - free, total)
    }
}

private data class DeviceInfo(val battery: Int, val usedRam: Double, val totalRam: Long, val usedStorage: Long, val totalStorage: Long)

@Composable
private fun SakuDesktop(
    loadApps: suspend () -> List<AppEntry>,
    openApp: (String) -> Unit,
    openSettings: () -> Unit,
    deviceInfo: () -> DeviceInfo
) {
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var listMode by remember { mutableStateOf(false) }
    var allApps by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(now("HH:mm")) }
    var note by remember { mutableStateOf("Selesaikan build Saku Simple\nRapikan widget Liquid Glass") }
    var info by remember { mutableStateOf(DeviceInfo(0, 0.0, 4, 0, 128)) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps() }
        info = withContext(Dispatchers.IO) { deviceInfo() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            clock = now("HH:mm")
            info = withContext(Dispatchers.IO) { deviceInfo() }
            kotlinx.coroutines.delay(30_000)
        }
    }
    BackHandler(enabled = allApps) { allApps = false; search = "" }
    val filtered = apps.filter { it.label.contains(search, true) }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF061326), Color(0xFF0A2B50), Color(0xFF071321)))
        ).windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).padding(10.dp)
    ) {
        val compact = maxWidth < 600.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // App shelf: always on the left, with grid/list switching.
            Surface(
                modifier = Modifier.fillMaxHeight().weight(if (compact) 0.82f else 0.78f),
                shape = RoundedCornerShape(24.dp), color = Panel, border = androidx.compose.foundation.BorderStroke(1.dp, Stroke)
            ) {
                Column(Modifier.fillMaxSize().padding(if (compact) 8.dp else 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(if (compact) 28.dp else 38.dp).clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(Glow, Color(0xFF7D8CFF)))),
                            contentAlignment = Alignment.Center) {
                            Text("S", color = Ink, fontWeight = FontWeight.Black, fontSize = 19.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text("SAKU", color = TextMain, fontWeight = FontWeight.Bold, fontSize = if (compact) 12.sp else 17.sp)
                            if (!compact) Text("DESKTOP", color = TextSoft, fontSize = 10.sp, letterSpacing = 2.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x332F6796)).padding(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, null, tint = TextSoft, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        BasicTextField(
                            value = search, onValueChange = { search = it },
                            modifier = Modifier.weight(1f), singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = TextMain, fontSize = 11.sp),
                            decorationBox = { inner ->
                                Box {
                                    if (search.isEmpty()) Text("Cari...", color = TextSoft, fontSize = 11.sp)
                                    inner()
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (allApps) "APLIKASI" else "FAVORIT", color = TextSoft, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                        Icon(if (listMode) Icons.Default.GridView else Icons.Default.List, "Ubah mode tampilan",
                            tint = Glow, modifier = Modifier.size(20.dp).clickable { listMode = !listMode; allApps = true })
                    }
                    Spacer(Modifier.height(6.dp))
                    if (listMode) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                            items(if (allApps || search.isNotEmpty()) filtered else apps.take(10), key = { it.packageName }) { app ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { openApp(app.packageName) }.padding(5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AppIcon(app, true)
                                    Spacer(Modifier.width(7.dp))
                                    Text(app.label, color = TextMain, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(if (compact) 2 else 3),
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(if (allApps || search.isNotEmpty()) filtered else apps.take(12), key = { it.packageName }) { app ->
                                AppTile(app, compact) { openApp(app.packageName) }
                            }
                        }
                    }
                    Surface(color = Color(0x332D85B7), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(7.dp)) {
                            Text("FAVORIT", color = TextSoft, fontSize = 8.sp, letterSpacing = 1.sp)
                            Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                apps.take(3).forEach { AppIcon(it, true) { openApp(it.packageName) } }
                            }
                        }
                    }
                }
            }

            // Scrollable widget column on the right.
            LazyColumn(
                modifier = Modifier.weight(1.35f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                item {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("SAKU DESKTOP", color = TextSoft, fontSize = 9.sp, letterSpacing = 1.2.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(clock, color = TextMain, fontSize = if (compact) 32.sp else 48.sp, fontWeight = FontWeight.Light)
                                Text(now("EEEE, d MMMM", Locale("id", "ID")), color = TextSoft, fontSize = 10.sp)
                            }
                            Icon(Icons.Default.CalendarMonth, null, tint = Glow, modifier = Modifier.size(24.dp))
                        }
                    }
                }
                item {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cloud, null, tint = Glow, modifier = Modifier.size(25.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Cuaca", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text("Sumber cuaca belum tersambung", color = TextSoft, fontSize = 9.sp)
                                Text("Lokasi dan prakiraan akan tampil di sini", color = TextSoft, fontSize = 8.sp)
                            }
                        }
                    }
                }
                item {
                    GlassCard {
                        Text("INFO PERANGKAT", color = TextSoft, fontSize = 9.sp, letterSpacing = 1.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            DeviceMetric("BAT", "${info.battery}%", "Baterai", Modifier.weight(1f))
                            DeviceMetric("RAM", String.format(Locale.US, "%.1f GB", info.usedRam), "dari ${info.totalRam} GB", Modifier.weight(1f))
                            DeviceMetric("ROM", "${info.usedStorage} GB", "dari ${info.totalStorage} GB", Modifier.weight(1f))
                        }
                    }
                }
                item {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.EditNote, null, tint = Glow, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("CATATAN", color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(7.dp))
                        BasicTextField(
                            value = note, onValueChange = { note = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 65.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(color = TextMain, fontSize = 11.sp, lineHeight = 17.sp),
                            decorationBox = { inner ->
                                Box {
                                    if (note.isEmpty()) Text("Tulis catatan...", color = TextSoft, fontSize = 11.sp)
                                    inner()
                                }
                            }
                        )
                        Text("Catatan sesi ini", color = TextSoft, fontSize = 8.sp)
                    }
                }
                item {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Wifi, null, tint = Glow, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("STATUS SISTEM", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, null, tint = Glow, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Column {
                                Text("Android ${android.os.Build.VERSION.RELEASE}", color = TextMain, fontSize = 11.sp)
                                Text("Saku Simple • berjalan", color = TextSoft, fontSize = 9.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, null, tint = Glow, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Penyimpanan internal", color = TextSoft, fontSize = 10.sp)
                        }
                    }
                }
                item {
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CalendarMonth, null, tint = Glow, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("KALENDER", color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(now("d MMMM yyyy", Locale("id", "ID")), color = TextMain, fontSize = 16.sp)
                        Text(now("EEEE", Locale("id", "ID")), color = TextSoft, fontSize = 10.sp)
                    }
                }
                item {
                    Surface(
                        color = Color(0x663C7FA7), shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable { openSettings() }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Settings, null, tint = TextMain, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Pengaturan Android", color = TextMain, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, Stroke),
        content = { Column(Modifier.padding(12.dp), content = content) }
    )
}

@Composable
private fun DeviceMetric(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x332D8FC7)).padding(7.dp)) {
        Text(title, color = Glow, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(value, color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(detail, color = TextSoft, fontSize = 8.sp, maxLines = 1)
    }
}

@Composable
private fun AppTile(app: AppEntry, compact: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp)
    ) {
        AppIcon(app, compact = false)
        Text(app.label, color = TextSoft, fontSize = if (compact) 8.sp else 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AppIcon(app: AppEntry, compact: Boolean = false, onClick: () -> Unit = {}) {
    val bitmap = remember(app.packageName) {
        val output = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        app.icon.setBounds(0, 0, 96, 96)
        app.icon.draw(canvas)
        output.asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = app.label,
        modifier = Modifier.size(if (compact) 28.dp else 34.dp)
            .clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
    )
}

private fun now(pattern: String, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat(pattern, locale).format(Date())
