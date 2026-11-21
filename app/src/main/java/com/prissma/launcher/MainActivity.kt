package com.prissma.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF090B0E),
                    surface = Color(0xFF141820),
                    primary = Color(0xFF00D68F)
                )
            ) {
                PrissmaWelcomeScreen()
            }
        }
    }
}

@Composable
fun PrissmaWelcomeScreen() {
    var statusText by remember { mutableStateOf("Sistema listo para construir") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090B0E))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "PRISSMA LAUNCHER",
            color = Color(0xFFF3F6FC),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Hub Tri-Motor para Android y Consolas Portátiles",
            color = Color(0xFF8E9BB0),
            fontSize = 15.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EngineBadge(label = "JAVA 26.3", color = Color(0xFFF59E0B))
            EngineBadge(label = "RUST 26.3", color = Color(0xFFE85D3F))
            EngineBadge(label = "BEDROCK 26.x", color = Color(0xFF38BDF8))
        }

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = { statusText = "Núcleo v0.1 activo en modo consola" },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00D68F),
                contentColor = Color(0xFF090B0E)
            )
        ) {
            Text(
                text = "Probar Sistema",
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = statusText,
            color = Color(0xFFA78BFA),
            fontSize = 14.sp
        )
    }
}

@Composable
fun EngineBadge(label: String, color: Color) {
    Text(
        text = label,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .border(1.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}
