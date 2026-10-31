package com.saku.dex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.saku.dex.data.AppLauncher
import com.saku.dex.data.InstalledAppsRepository
import com.saku.dex.model.LauncherApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DesktopScreen() {
    val context = LocalContext.current
    val repository = remember(context) { InstalledAppsRepository(context.packageManager) }
    var apps by remember { mutableStateOf<List<LauncherApp>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var drawerOpen by remember { mutableStateOf(false) }
    val desktopGridState = rememberLazyGridState()
    val drawerGridState = rememberLazyGridState()

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
                        state = desktopGridState,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                        onAppClick = { AppLauncher.launch(context, it.packageName) }
                    )
                } else {
                    // Do not keep a second lazy grid active underneath the open drawer.
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
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f))
                    .clickable { drawerOpen = false }
            )
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(bottom = 58.dp, start = 10.dp, end = 10.dp)
                    .heightIn(min = 300.dp, max = 520.dp),
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                color = Color(0xF01B2734),
                shadowElevation = 16.dp
            ) {
                Column(Modifier.fillMaxWidth().heightIn(min = 276.dp, max = 496.dp).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        "Aplikasi",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                    )
                    if (apps.isEmpty()) {
                        Text("Tidak ada aplikasi.", color = Color.White)
                    } else {
                        AppGrid(
                            apps = apps,
                            minCellSize = 76,
                            state = drawerGridState,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            onAppClick = {
                                drawerOpen = false
                                AppLauncher.launch(context, it.packageName)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppGrid(
    apps: List<LauncherApp>,
    minCellSize: Int,
    state: LazyGridState,
    modifier: Modifier = Modifier,
    onAppClick: (LauncherApp) -> Unit
) {
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(minSize = minCellSize.dp),
        modifier = modifier,
        contentPadding = PaddingValues(top = 10.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items = apps,
            key = { it.packageName },
            contentType = { "launcher-app" }
        ) { app ->
            AppGridItem(app) { onAppClick(app) }
        }
    }
}

@Composable
private fun AppGridItem(app: LauncherApp, onClick: () -> Unit) {
    val iconImage = remember(app.packageName, app.icon) { app.icon.asImageBitmap() }
    Column(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = iconImage,
            contentDescription = app.name,
            modifier = Modifier.size(48.dp)
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
