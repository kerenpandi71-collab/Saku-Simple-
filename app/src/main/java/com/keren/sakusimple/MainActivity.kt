package com.keren.sakusimple

import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.os.BatteryManager
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
private val Panel = Color(0xB8102C4B)
private val Stroke = Color(0x66B8E8FF)
private val Glow = Color(0xFF69DDF5)
private val LocalGlassEnabled = androidx.compose.runtime.staticCompositionLocalOf { true }
private val LocalGlassOpacity = androidx.compose.runtime.staticCompositionLocalOf { 0.62f }

data class AppEntry(val label: String, val packageName: String, val icon: ImageBitmap)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
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
                AppEntry(info.loadLabel(packageManager).toString(), pkg, drawableToBitmap(info.loadIcon(packageManager)))
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun launchPackage(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            // Launch from the current launcher activity/task. Adding NEW_TASK here can
            // make Back return to another task (for example, the stock launcher).
            runCatching { startActivity(launchIntent) }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("saku_simple_settings", android.content.Context.MODE_PRIVATE) }
    var showSimpleSettings by remember { mutableStateOf(false) }
    var glassEnabled by remember { mutableStateOf(prefs.getBoolean("glass_enabled", true)) }
    var glassOpacity by remember { mutableFloatStateOf(prefs.getFloat("glass_opacity", 0.62f)) }
    var fontScale by remember { mutableFloatStateOf(prefs.getFloat("font_scale", 1f).coerceIn(0.8f, 1.4f)) }
    var iconSize by remember { mutableFloatStateOf(prefs.getFloat("icon_size", 34f).coerceIn(24f, 64f)) }
    var gridColumns by remember { mutableIntStateOf(prefs.getInt("grid_columns", 3).coerceIn(2, 3)) }
    var hideGridLabels by remember { mutableStateOf(prefs.getBoolean("hide_grid_labels", false)) }
    var wallpaperBlur by remember { mutableFloatStateOf(prefs.getFloat("wallpaper_blur", 0f).coerceIn(0f, 30f)) }
    val activityWindow = remember(context) { (context as? android.app.Activity)?.window }
    SideEffect {
        // The system wallpaper behind a transparent window remains sharp. When blur is
        // enabled, cover that layer so only the blurred wallpaper bitmap is visible.
        activityWindow?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                if (wallpaperBlur > 0f) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT
            )
        )
    }
    var systemWallpaper by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) {
        systemWallpaper = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = android.app.WallpaperManager.getInstance(context).drawable
                    ?: return@withContext null
                val metrics = context.resources.displayMetrics
                val width = metrics.widthPixels.coerceIn(1, 1600)
                val height = metrics.heightPixels.coerceIn(1, 3000)
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
                bitmap.asImageBitmap()
            }.getOrNull()
        }
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps() }
        info = withContext(Dispatchers.IO) { deviceInfo() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            clock = now("HH:mm")
            kotlinx.coroutines.delay(60_000)
            info = withContext(Dispatchers.IO) { deviceInfo() }
        }
    }
    // Keep the launcher Activity alive when Back is pressed on the desktop.
    // Handle transient UI first, then consume Back on the home surface.
    BackHandler(enabled = true) {
        when {
            showSimpleSettings -> showSimpleSettings = false
            allApps -> { allApps = false; search = "" }
            search.isNotEmpty() -> search = ""
            else -> Unit // Stay on the launcher desktop instead of finishing/recreating it.
        }
    }
    val filtered = apps.filter { it.label.contains(search, true) }

    CompositionLocalProvider(LocalDensity provides Density(density = LocalDensity.current.density, fontScale = fontScale), LocalGlassEnabled provides glassEnabled, LocalGlassOpacity provides glassOpacity) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Transparent).windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).padding(10.dp)
    ) {
        val compact = maxWidth < 600.dp
        systemWallpaper?.let { wallpaperImage ->
            Image(
                bitmap = wallpaperImage,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    if (android.os.Build.VERSION.SDK_INT >= 31 && wallpaperBlur > 0f) {
                        renderEffect = android.graphics.RenderEffect.createBlurEffect(
                            wallpaperBlur * density, wallpaperBlur * density,
                            android.graphics.Shader.TileMode.CLAMP
                        ).asComposeRenderEffect()
                    } else {
                        renderEffect = null
                    }
                }
            )
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // App shelf: always on the left, with grid/list switching.
            Surface(
                modifier = Modifier.fillMaxHeight().weight(if (compact) 0.82f else 0.78f),
                shape = RoundedCornerShape(24.dp), color = if (glassEnabled) Panel.copy(alpha = glassOpacity * 0.62f) else Color(0xFF102C4B), border = androidx.compose.foundation.BorderStroke(1.dp, if (glassEnabled) Stroke else Stroke.copy(alpha = 0.35f))
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
                        Text("APLIKASI", color = TextSoft, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                        Icon(if (listMode) Icons.Default.GridView else Icons.Default.List, "Ubah mode tampilan",
                            tint = Glow, modifier = Modifier.size(20.dp).clickable { listMode = !listMode; allApps = true })
                    }
                    Spacer(Modifier.height(6.dp))
                    key(listMode) {
                        AndroidView(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            factory = { context ->
                                val adapter = AppShelfAdapter(context, listMode, iconSize.toInt(), hideGridLabels) { packageName -> openApp(packageName) }
                                if (listMode) {
                                    ListView(context).apply {
                                        id = View.generateViewId()
                                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                        divider = null
                                        dividerHeight = 0
                                        selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                                        clipToPadding = false
                                        this.adapter = adapter
                                        tag = adapter
                                    }
                                } else {
                                    GridView(context).apply {
                                        id = View.generateViewId()
                                        numColumns = gridColumns
                                        horizontalSpacing = (3 * resources.displayMetrics.density).toInt()
                                        verticalSpacing = (8 * resources.displayMetrics.density).toInt()
                                        stretchMode = GridView.STRETCH_COLUMN_WIDTH
                                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                        selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                                        clipToPadding = false
                                        this.adapter = adapter
                                        tag = adapter
                                    }
                                }
                            },
                            update = { view ->
                                (view.tag as? AppShelfAdapter)?.apply { configure(iconSize.toInt(), hideGridLabels); submitItems(filtered) }
                                if (view is GridView) view.numColumns = gridColumns
                            }
                        )
                    }
                }
            }

            // Native Android widget shelf: avoid Compose LazyColumn's scroll overhead on low-memory devices.
            AndroidView(
                modifier = Modifier.weight(1.35f).fillMaxHeight(),
                factory = { context ->
                    val density = context.resources.displayMetrics.density
                    fun dp(v: Int) = (v * density).toInt()
                    val ink = android.graphics.Color.rgb(235, 244, 255)
                    val soft = android.graphics.Color.rgb(157, 179, 205)
                    val glow = android.graphics.Color.rgb(91, 207, 255)
                    val panel = android.graphics.Color.argb(if (glassEnabled) (glassOpacity * 255).toInt().coerceIn(90, 220) else 255, 20, 35, 58)
                    val root = android.widget.ScrollView(context).apply {
                        isFillViewport = false
                        clipToPadding = false
                        setPadding(0, 0, 0, dp(10))
                        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                        isVerticalScrollBarEnabled = false
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                    val column = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, 0, 0, dp(4))
                    }
                    fun card(title: String? = null): LinearLayout {
                        val box = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(panel)
                                cornerRadius = dp(18).toFloat()
                                setStroke(dp(1), if (glassEnabled) android.graphics.Color.argb(125, 190, 232, 255) else android.graphics.Color.rgb(55, 84, 116))
                            }
                        }
                        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        params.bottomMargin = dp(8)
                        box.elevation = dp(if (glassEnabled) 3 else 0).toFloat()
                        column.addView(box, params)
                        if (title != null) {
                            val heading = TextView(context).apply {
                                text = title
                                textSize = 12f
                                setTextColor(ink)
                                setTypeface(null, android.graphics.Typeface.BOLD)
                            }
                            box.addView(heading)
                            heading.setPadding(0, 0, 0, dp(7))
                        }
                        return box
                    }
                    fun label(text: String, size: Float = 11f, color: Int = soft, bold: Boolean = false): TextView =
                        TextView(context).apply {
                            this.text = text
                            textSize = size
                            setTextColor(color)
                            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
                        }
                    val clockCard = card()
                    clockCard.addView(label("SAKU DESKTOP", 9f, soft, true))
                    clockCard.addView(label(clock, if (compact) 32f else 44f, ink).apply { tag = "saku_clock" })
                    clockCard.addView(label(now("EEEE, d MMMM", Locale("id", "ID")), 10f, soft).apply { tag = "saku_date" })

                    val weather = card()
                    weather.addView(label("☁  Cuaca", 15f, ink, true))
                    weather.addView(label("Sumber cuaca belum tersambung", 9f, soft))
                    weather.addView(label("Lokasi dan prakiraan akan tampil di sini", 8f, soft))

                    val device = card("INFO PERANGKAT")
                    val metrics = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    fun metric(title: String, value: String, detail: String): LinearLayout {
                        val item = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(6), dp(7), dp(4), dp(7))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.argb(if (glassEnabled) 125 else 255, 34, 65, 94))
                                cornerRadius = dp(10).toFloat()
                            }
                        }
                        item.addView(label(title, 8f, glow, true))
                        item.addView(label(value, 10f, ink, true).apply { maxLines = 1; tag = "metric_$title" })
                        item.addView(label(detail, 7f, soft).apply { maxLines = 1; tag = "detail_$title" })
                        val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        p.marginEnd = dp(3)
                        metrics.addView(item, p)
                        return item
                    }
                    metric("BAT", "${info.battery}%", "Baterai")
                    metric("RAM", String.format(Locale.US, "%.1f GB", info.usedRam), "dari ${info.totalRam} GB")
                    metric("ROM", "${info.usedStorage} GB", "dari ${info.totalStorage} GB")
                    device.addView(metrics)

                    val notes = card("✎  CATATAN")
                    val edit = android.widget.EditText(context).apply {
                        setText(note)
                        setTextColor(ink)
                        setHintTextColor(soft)
                        hint = "Tulis catatan..."
                        textSize = 11f
                        minHeight = dp(65)
                        gravity = android.view.Gravity.TOP
                        setPadding(0, dp(4), 0, dp(4))
                        background = null
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { note = s?.toString().orEmpty() }
                            override fun afterTextChanged(s: android.text.Editable?) {}
                        })
                    }
                    notes.addView(edit)
                    notes.addView(label("Catatan sesi ini", 8f, soft))

                    val status = card("◉  STATUS SISTEM")
                    status.addView(label("Android ${android.os.Build.VERSION.RELEASE}", 11f, ink))
                    status.addView(label("Saku Simple • berjalan", 9f, soft))
                    status.addView(label("▤  Penyimpanan internal", 10f, soft))

                    val calendar = card("▦  KALENDER")
                    calendar.addView(label(now("d MMMM yyyy", Locale("id", "ID")), 16f, ink).apply { tag = "saku_calendar_date" })
                    calendar.addView(label(now("EEEE", Locale("id", "ID")), 10f, soft).apply { tag = "saku_calendar_day" })

                    fun actionCard(title: String, subtitle: String? = null, action: () -> Unit) {
                        val item = card()
                        item.orientation = LinearLayout.HORIZONTAL
                        item.gravity = android.view.Gravity.CENTER_VERTICAL
                        item.isClickable = true
                        item.isFocusable = true
                        item.setOnClickListener { action() }
                        val textColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                        textColumn.addView(label(title, 11f, ink, true))
                        if (subtitle != null) textColumn.addView(label(subtitle, 8f, soft))
                        item.addView(textColumn)
                        item.background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(android.graphics.Color.rgb(42, 74, 105))
                            cornerRadius = dp(14).toFloat()
                        }
                    }
                    actionCard("⚙  Pengaturan Android") { openSettings() }
                    actionCard("⚙  Pengaturan Simple", "Tampilan, font, ikon, label") { showSimpleSettings = true }

                    root.addView(column, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ))
                    root.tag = column
                    root
                },
                update = { view ->
                    val column = view.tag as? LinearLayout
                    column?.findViewWithTag<TextView>("saku_clock")?.text = clock
                    column?.findViewWithTag<TextView>("saku_date")?.text = now("EEEE, d MMMM", Locale("id", "ID"))
                    column?.findViewWithTag<TextView>("saku_calendar_date")?.text = now("d MMMM yyyy", Locale("id", "ID"))
                    column?.findViewWithTag<TextView>("saku_calendar_day")?.text = now("EEEE", Locale("id", "ID"))
                    column?.findViewWithTag<TextView>("metric_BAT")?.text = "${info.battery}%"
                    column?.findViewWithTag<TextView>("metric_RAM")?.text = String.format(Locale.US, "%.1f GB", info.usedRam)
                    column?.findViewWithTag<TextView>("metric_ROM")?.text = "${info.usedStorage} GB"
                }
            )
        }
    }
    }
    if (showSimpleSettings) SimpleSettingsDialog(glassEnabled, { glassEnabled=it; prefs.edit().putBoolean("glass_enabled",it).apply() }, glassOpacity, { glassOpacity=it; prefs.edit().putFloat("glass_opacity",it).apply() }, fontScale, { fontScale=it; prefs.edit().putFloat("font_scale",it).apply() }, iconSize, { iconSize=it; prefs.edit().putFloat("icon_size",it).apply() }, gridColumns, { gridColumns=it; prefs.edit().putInt("grid_columns",it).apply() }, hideGridLabels, { hideGridLabels=it; prefs.edit().putBoolean("hide_grid_labels",it).apply() }, wallpaperBlur, { wallpaperBlur=it; prefs.edit().putFloat("wallpaper_blur",it).apply() }, { showSimpleSettings=false })
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    val glass = LocalGlassEnabled.current
    val opacity = LocalGlassOpacity.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (glass) Panel.copy(alpha = opacity) else Panel,
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
    Image(
        bitmap = app.icon,
        contentDescription = app.label,
        modifier = Modifier.size(if (compact) 28.dp else 34.dp)
            .clickable(onClick = onClick)
    )
}


private class AppShelfAdapter(
    private val context: android.content.Context,
    private val listMode: Boolean,
    initialIconSize: Int,
    initialHideGridLabels: Boolean,
    private val onLaunch: (String) -> Unit
) : BaseAdapter() {
    private var entries: List<AppEntry> = emptyList()
    private var iconSize = initialIconSize
    private var hideGridLabels = initialHideGridLabels
    private val density = context.resources.displayMetrics.density
    fun configure(size: Int, hideLabels: Boolean) { if (iconSize != size || hideGridLabels != hideLabels) { iconSize=size; hideGridLabels=hideLabels; notifyDataSetChanged() } }

    fun submitItems(items: List<AppEntry>) {
        if (entries === items || entries == items) return
        entries = items
        notifyDataSetChanged()
    }

    override fun getCount(): Int = entries.size
    override fun getItem(position: Int): AppEntry = entries[position]
    override fun getItemId(position: Int): Long = entries[position].packageName.hashCode().toLong()
    override fun hasStableIds(): Boolean = true

    private data class Holder(val icon: ImageView, val label: TextView)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val root: View
        val holder: Holder
        if (convertView == null) {
            val layout = LinearLayout(context).apply {
                orientation = if (listMode) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(if (listMode) 7 else 2), dp(5), dp(2), dp(5))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    cornerRadius = dp(10).toFloat()
                }
                isFocusable = false
                isClickable = true
            }
            val icon = ImageView(context).apply {
                layoutParams = if (listMode) {
                    LinearLayout.LayoutParams(dp(iconSize), dp(iconSize))
                } else {
                    LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val label = TextView(context).apply {
                setTextColor(android.graphics.Color.rgb(187, 208, 232))
                textSize = if (listMode) 11f else 9f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = if (listMode) android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.CENTER
                if (listMode) {
                    setPadding(dp(8), 0, dp(2), 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                } else {
                    setPadding(0, dp(3), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }
            layout.addView(icon)
            layout.addView(label)
            holder = Holder(icon, label)
            layout.tag = holder
            root = layout
        } else {
            root = convertView
            holder = root.tag as Holder
        }

        val entry = getItem(position)
        val displayIconSize = if (listMode) maxOf(iconSize, 40) else iconSize
        holder.icon.layoutParams = holder.icon.layoutParams.apply { width = dp(displayIconSize); height = dp(displayIconSize) }
        holder.icon.setImageBitmap(entry.icon.asAndroidBitmap())
        holder.label.visibility = if (!listMode && hideGridLabels) View.GONE else View.VISIBLE
        holder.label.text = entry.label
        root.contentDescription = entry.label
        root.setOnClickListener { onLaunch(entry.packageName) }
        return root
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}

@Composable
private fun SimpleSettingsDialog(glass: Boolean, onGlass: (Boolean)->Unit, opacity: Float, onOpacity: (Float)->Unit, scale: Float, onScale: (Float)->Unit, icon: Float, onIcon: (Float)->Unit, columns: Int, onColumns: (Int)->Unit, hide: Boolean, onHide: (Boolean)->Unit, blur: Float, onBlur: (Float)->Unit, dismiss: ()->Unit) {
 AlertDialog(onDismissRequest=dismiss, containerColor=Color(0xFF102C4B),
  title={ Column { Text("Pengaturan Simple",color=TextMain,fontWeight=FontWeight.Bold); Text("Personalisasi Saku Desktop",color=TextSoft,fontSize=11.sp) } },
  text={ Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState())) {
   SettingsSwitchRow("Liquid Glass","Efek panel kaca",glass,onGlass)
   if(glass){ Text("Transparansi "+(opacity*100).toInt()+"%",color=TextSoft,fontSize=11.sp); Slider(value=opacity,onValueChange=onOpacity,valueRange=0.35f..0.88f) }
   SettingsDivider()
   Text("Ukuran font Saku: "+(scale*100).toInt()+"%",color=TextMain,fontWeight=FontWeight.SemiBold)
   Text("Terpisah dari ukuran font Android",color=TextSoft,fontSize=10.sp)
   Slider(value=scale,onValueChange=onScale,valueRange=0.8f..1.4f)
   SettingsDivider()
   Text("Kolom grid aplikasi",color=TextMain,fontWeight=FontWeight.SemiBold)
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
    FilterChip(selected=columns==2,onClick={onColumns(2)},label={Text("2 kolom",fontSize=10.sp)})
    FilterChip(selected=columns==3,onClick={onColumns(3)},label={Text("3 kolom",fontSize=10.sp)})
   }
   Text("Pilih jumlah kolom saat mode grid aktif.",color=TextSoft,fontSize=9.sp)
   SettingsDivider()
   Text("Ukuran ikon grid: "+icon.toInt()+" dp",color=TextMain,fontWeight=FontWeight.SemiBold)
   Slider(value=icon,onValueChange=onIcon,valueRange=24f..64f)
   Text("List memakai ukuran ikon tetap.",color=TextSoft,fontSize=9.sp)
   SettingsDivider()
   SettingsSwitchRow("Sembunyikan label grid","Hanya grid; list tetap menampilkan nama",hide,onHide)
   SettingsDivider()
   Text("Blur wallpaper: "+blur.toInt()+" dp",color=TextMain,fontWeight=FontWeight.SemiBold)
   Slider(value=blur,onValueChange=onBlur,valueRange=0f..30f)
   Text("0 = tajam • 30 = blur kuat. Efek berlaku pada wallpaper di belakang launcher.",color=TextSoft,fontSize=10.sp)
   SettingsDivider()
   Text("Wallpaper sistem Android",color=TextMain,fontWeight=FontWeight.SemiBold)
   Text("Wallpaper Android aktif digunakan sebagai latar Saku Simple.",color=TextSoft,fontSize=10.sp)
  } },
  confirmButton={ TextButton(onClick=dismiss){Text("Selesai",color=Glow)} })
}
@Composable private fun SettingsSwitchRow(title:String,subtitle:String,checked:Boolean,onChecked:(Boolean)->Unit){
 Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
  Column(Modifier.weight(1f)){Text(title,color=TextMain,fontWeight=FontWeight.SemiBold,fontSize=12.sp);Text(subtitle,color=TextSoft,fontSize=9.sp)}
  Switch(checked=checked,onCheckedChange=onChecked)
 }
}
@Composable private fun SettingsDivider(){Spacer(Modifier.height(8.dp));HorizontalDivider(color=Glow.copy(alpha=0.25f));Spacer(Modifier.height(8.dp))}

private fun drawableToBitmap(drawable: Drawable): ImageBitmap {
    // Small app icons are prepared while the app list loads on Dispatchers.IO.
    val size = 48
    val output = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return output.asImageBitmap()
}

private fun now(pattern: String, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat(pattern, locale).format(Date())
