package com.saku.dex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.saku.dex.data.AppLauncher
import com.saku.dex.data.InstalledAppsRepository
import com.saku.dex.model.LauncherApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DesktopScreen() {
    val context = LocalContext.current
    val repository = remember(context) { InstalledAppsRepository(context) }
    var apps by remember { mutableStateOf<List<LauncherApp>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var drawerOpen by remember { mutableStateOf(false) }
    var listMode by remember { mutableStateOf(false) }
    var drawerScrollPosition by remember { mutableIntStateOf(0) }
    var drawerScrollOffset by remember { mutableIntStateOf(0) }

    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    LaunchedEffect(repository) {
        isLoading = true
        apps = withContext(Dispatchers.IO) { repository.getLaunchableApps() }
        isLoading = false
    }

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF26364A), Color(0xFF101820)))
        )
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Saku DeX",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp)
            )
            when {
                isLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
                apps.isEmpty() -> Box(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Belum ada aplikasi yang bisa ditampilkan.",
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
                else -> if (!drawerOpen) {
                    AppGrid(
                        apps = apps,
                        minCellSize = 88,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                        onAppClick = { AppLauncher.launch(context, it) },
                        onAppLongClick = { AppLauncher.launchInWindow(context, it) }
                    )
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth())
                }
            }
            Taskbar(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                onStartClick = { drawerOpen = !drawerOpen },
                onDesktopClick = { drawerOpen = false }
            )
        }

        if (drawerOpen) {
            Dialog(
                onDismissRequest = { drawerOpen = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth().fillMaxHeight(0.76f)
                            .padding(bottom = 66.dp, start = 10.dp, end = 10.dp)
                            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)),
                        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                        color = Color(0xFF1B2734),
                        shadowElevation = 18.dp
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Aplikasi",
                                    color = Color.White,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    DrawerModeButton("Grid", selected = !listMode) { listMode = false }
                                    DrawerModeButton("List", selected = listMode) { listMode = true }
                                    DrawerModeButton("Tutup", selected = false) { drawerOpen = false }
                                }
                            }
                            if (apps.isEmpty()) {
                                Text("Tidak ada aplikasi.", color = Color.White)
                            } else {
                                AppDrawerRecyclerView(
                                    apps = apps,
                                    listMode = listMode,
                                    scrollPosition = drawerScrollPosition,
                                    scrollOffset = drawerScrollOffset,
                                    onScrollChanged = { position, offset ->
                                        drawerScrollPosition = position
                                        drawerScrollOffset = offset
                                    },
                                    modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                                    onAppClick = {
                                        drawerOpen = false
                                        AppLauncher.launch(context, it)
                                    },
                                    onAppLongClick = {
                                        drawerOpen = false
                                        AppLauncher.launchInWindow(context, it)
                                    }
                                )
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .size(42.dp)
                        .background(Color(0xFF303D4B), RoundedCornerShape(10.dp))
                        .clickable { drawerOpen = false },
                    contentAlignment = Alignment.Center
                ) {
                    Text("▦", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppGrid(
    apps: List<LauncherApp>,
    minCellSize: Int,
    modifier: Modifier = Modifier,
    onAppClick: (LauncherApp) -> Unit,
    onAppLongClick: (LauncherApp) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = minCellSize.dp),
        modifier = modifier,
        contentPadding = PaddingValues(top = 10.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(apps, key = { it.componentName }) { app ->
            Column(
                modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { onAppClick(app) },
                        onLongClick = { onAppLongClick(app) }
                    )
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    bitmap = remember(app.componentName, app.icon) { app.icon.asImageBitmap() },
                    contentDescription = app.name,
                    modifier = Modifier.size(48.dp)
                        .shadow(3.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                )
                Text(
                    text = app.name,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun DrawerModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) Color(0xFF426A91) else Color(0xFF293746),
        contentColor = Color.White
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
