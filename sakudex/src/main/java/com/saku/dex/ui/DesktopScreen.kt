package com.saku.dex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
                        onAppClick = { AppLauncher.launch(context, it) }
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
                Box(Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth().fillMaxHeight(0.76f)
                            .padding(bottom = 58.dp, start = 10.dp, end = 10.dp),
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
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    onAppClick = {
                                        drawerOpen = false
                                        AppLauncher.launch(context, it)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
