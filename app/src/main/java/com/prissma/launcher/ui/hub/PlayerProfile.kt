package com.prissma.launcher.ui.hub

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.prissma.launcher.data.SavedProfile
import com.prissma.launcher.engine.java.gameLanguages
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.components.gamepadClick
import com.prissma.launcher.ui.theme.Prissma
import java.io.File

@Composable
internal fun ProfileAvatar(label: String, photo: Any?, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(Prissma.colors.surfaceElevated), contentAlignment = Alignment.Center) {
        Text(label.take(2).uppercase(), color = Prissma.colors.brandAccent, fontWeight = FontWeight.Bold)
        if (photo != null) AsyncImage(photo, "Foto de perfil", Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun ProfileDialog(
    profile: SavedProfile,
    avatarFile: File?,
    onDismiss: () -> Unit,
    saving: Boolean,
    onSave: (SavedProfile, Uri?) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(profile.playerName) }
    var display by rememberSaveable { mutableStateOf(profile.displayName) }
    var bio by rememberSaveable { mutableStateOf(profile.bio) }
    var playStyle by rememberSaveable { mutableStateOf(profile.playStyle) }
    var language by rememberSaveable { mutableStateOf(profile.language) }
    var type by rememberSaveable { mutableStateOf(if (profile.accountType.equals("Microsoft", ignoreCase = true)) "Microsoft" else "Offline") }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    var removePhoto by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { image ->
        if (image != null) {
            photo = image.toString()
            removePhoto = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Perfil de jugador") },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ProfileAvatar(display.ifBlank { name }, photo?.let(Uri::parse) ?: avatarFile?.takeUnless { removePhoto },
                        Modifier.size(52.dp))
                    LauncherButton("Elegir foto", { picker.launch("image/*") }, enabled = !saving)
                    if (photo != null || (avatarFile != null && !removePhoto)) {
                        TextButton({ photo = null; removePhoto = true }, enabled = !saving) { Text("Quitar") }
                    }
                }
                OutlinedTextField(name, { name = it.take(32) }, Modifier.fillMaxWidth(),
                    label = { Text("Usuario para jugar Offline") }, singleLine = true)
                OutlinedTextField(display, { display = it.take(32) }, Modifier.fillMaxWidth(),
                    label = { Text("Nombre en PRISSMA") }, singleLine = true)
                OutlinedTextField(bio, { bio = it.take(240) }, Modifier.fillMaxWidth(),
                    label = { Text("Sobre mí") }, maxLines = 3)
                OutlinedTextField(playStyle, { playStyle = it.take(40) }, Modifier.fillMaxWidth(),
                    label = { Text("Modo o estilo favorito") }, singleLine = true)
                Text("Idioma preferido para nuevas partidas", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    gameLanguages.forEach { (code, label) ->
                        val select = { language = code }
                        FilterChip(language == code, select, { Text(label) }, modifier = Modifier.gamepadClick(onClick = select))
                    }
                }
                Text("Tipo de cuenta", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Offline", "Microsoft").forEach {
                        val select = { type = it }
                        FilterChip(type == it, select, { Text(it) }, modifier = Modifier.gamepadClick(onClick = select))
                    }
                }
                if (type == "Microsoft") MicrosoftAccountPanel(enabled = !saving)
                Text("Estos datos y la foto se guardan en tu dispositivo.", color = Prissma.colors.textMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            LauncherButton(if (saving) "Guardando…" else "Guardar", {
                onSave(SavedProfile(name.trim(), type, display.trim(), bio.trim(), playStyle.trim(), language,
                    profile.avatar.takeUnless { removePhoto }), photo?.let(Uri::parse))
            }, primary = true, enabled = name.isNotBlank() && !saving)
        },
        dismissButton = { LauncherButton("Cancelar", onDismiss, enabled = !saving) }
    )
}
