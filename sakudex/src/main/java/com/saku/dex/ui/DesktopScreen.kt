package com.saku.dex.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.saku.dex.data.InstalledAppsRepository
import com.saku.dex.model.LauncherApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DesktopScreen() {
    val context = LocalContext.current
    val repository = remember(context) {
        InstalledAppsRepository(context.packageManager)
    }

    var apps by remember { mutableStateOf<List<LauncherApp>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(repository) {
        isLoading = true
        apps = withContext(Dispatchers.IO) {
            repository.getLaunchableApps()
        }
        isLoading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF26364A),
                        Color(0xFF101820)
                    )
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Saku DeX",
                color = Color.White,
                fontSize = 22.sp,
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp)
            )

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                apps.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Belum ada aplikasi yang bisa ditampilkan.",
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 88.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        contentPadding = PaddingValues(
                            top = 10.dp,
                            bottom = 14.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = apps,
                            key = { app -> app.packageName }
                        ) { app ->
                            AppGridItem(
                                app = app,
                                onClick = { launchApp(context, app.packageName) }
                            )
                        }
                    }
                }
            }

            Taskbar(
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun AppGridItem(
    app: LauncherApp,
    onClick: () -> Unit
) {
    val iconBitmap = remember(app.icon) {
        app.icon.toBitmap(width = 144, height = 144).asImageBitmap()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = iconBitmap,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
        )
    }
}

private fun launchApp(
    context: android.content.Context,
    packageName: String
) {
    try {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(packageName)

        if (launchIntent == null) {
            Toast.makeText(
                context,
                "Aplikasi tidak menyediakan launcher activity",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Aplikasi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "Izin membuka aplikasi ditolak", Toast.LENGTH_SHORT).show()
    }
}
