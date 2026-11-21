package com.prissma.launcher.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prissma.launcher.ui.components.PrissmaMark
import com.prissma.launcher.ui.theme.Prissma
import kotlinx.coroutines.delay

@Composable
fun PrissmaSplashScreen(onSplashFinished: () -> Unit) {
    val alpha = remember { Animatable(.45f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(220))
        delay(240)
        onSplashFinished()
    }

    Box(Modifier.fillMaxSize().background(Prissma.colors.surfaceVoid), contentAlignment = Alignment.Center) {
        Column(
            Modifier.alpha(alpha.value), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PrissmaMark(Modifier.size(61.dp))
            Text("PRISSMA", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        }
    }
}
