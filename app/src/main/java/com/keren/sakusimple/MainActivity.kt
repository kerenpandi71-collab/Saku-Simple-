package com.keren.sakusimple

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Night = Color(0xFF071426)
private val Glass = Color(0xBFFFFFFF)

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        setContent {
            MaterialTheme {
                SakuHome(
                    loadApps = { loadLaunchableApps() },
                    openApp = { packageName -> launchPackage(packageName) },
                    openSettings = {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    }
                )
            }
        }
    }

    private fun loadLaunchableApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                AppEntry(info.loadLabel(packageManager).toString(), packageName, info.loadIcon(packageManager))
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun launchPackage(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(launch) }
        }
    }
}

@Composable
private fun SakuHome(
    loadApps: suspend () -> List<AppEntry>,
    openApp: (String) -> Unit,
    openSettings: () -> Unit
) {
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var showAllApps by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(currentTime()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            clock = currentTime()
            kotlinx.coroutines.delay(30_000)
        }
    }
    BackHandler(enabled = showAllApps) { showAllApps = false }

    val filtered = apps.filter { it.label.contains(search, ignoreCase = true) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF102C52), Color(0xFF172D45), Color(0xFF071321))
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(clock, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Light)
                    Text(
                        SimpleDateFormat("EEEE, d MMMM", Locale("id", "ID")).format(Date()),
                        color = Color(0xFFD0E3F6),
                        fontSize = 14.sp
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = Glass,
                    modifier = Modifier.size(46.dp).clickable { openSettings() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Settings, contentDescription = "Pengaturan", tint = Night)
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color(0x33FFFFFF),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Ruang yang sederhana.", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Semua yang kamu butuhkan, dekat.", color = Color(0xFFD5E5F6), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showAllApps) "Semua aplikasi" else "Aplikasi",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = Color(0x332FFFFFFF),
                    shape = CircleShape,
                    modifier = Modifier.size(42.dp).clickable {
                        showAllApps = !showAllApps
                        search = ""
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Apps, contentDescription = "Semua aplikasi", tint = Color.White)
                    }
                }
            }
            AnimatedVisibility(visible = showAllApps) {
                TextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Cari aplikasi") },
                    shape = RoundedCornerShape(20.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0x33FFFFFF),
                        unfocusedContainerColor = Color(0x22FFFFFF),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedPlaceholderColor = Color(0xFFCDDAE8),
                        unfocusedPlaceholderColor = Color(0xFFCDDAE8),
                        focusedLeadingIconColor = Color.White,
                        unfocusedLeadingIconColor = Color.White
                    )
                )
            }
            Spacer(Modifier.height(12.dp))
            val visibleApps = if (showAllApps) filtered else apps.take(12)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(visibleApps, key = { it.packageName }) { app ->
                    AppIcon(app = app, onClick = { openApp(app.packageName) })
                }
            }
            Surface(
                color = Color(0x66DDEEFF),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    apps.take(4).forEach { app ->
                        AppIcon(app = app, compact = true, onClick = { openApp(app.packageName) })
                    }
                    if (apps.isEmpty()) {
                        Text("Saku Simple", color = Color.White, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: AppEntry, compact: Boolean = false, onClick: () -> Unit) {
    val bitmap = remember(app.packageName) {
        val drawable = app.icon
        val width = 96
        val height = 96
        val output = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        output.asImageBitmap()
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = app.label,
            modifier = Modifier.size(if (compact) 44.dp else 52.dp)
                .clip(RoundedCornerShape(14.dp))
        )
        if (!compact) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = app.label,
                color = Color.White,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

private fun currentTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
