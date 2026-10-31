package com.saku.dex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DesktopScreen() {
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
        Text(
            text = "Saku DeX",
            color = Color.White,
            fontSize = 28.sp,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 58.dp)
        )

        Taskbar(
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
