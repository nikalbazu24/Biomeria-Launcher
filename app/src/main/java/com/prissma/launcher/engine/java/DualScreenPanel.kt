package com.prissma.launcher.engine.java

import android.app.Presentation
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.prissma.launcher.ui.launch.GameHudControls
import com.prissma.launcher.ui.launch.PanelPreferenceControls
import com.prissma.launcher.ui.theme.Prissma
import com.prissma.launcher.ui.theme.PrissmaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun DualScreenHost(
    activity: JavaGameActivity,
    enabled: Boolean,
    bridge: DualScreenBridge?,
    title: String,
    hud: GameHudSettings,
    allowGameInput: Boolean,
    allowMenus: Boolean,
    onHud: (GameHudSettings) -> Unit,
    onHotbar: (Int) -> Unit
) {
    val content by rememberUpdatedState<@Composable () -> Unit>({
        PrissmaTheme { DualScreenPanel(title, bridge, hud, allowGameInput, allowMenus, onHud, onHotbar) }
    })
    DisposableEffect(activity, enabled, bridge) {
        val manager = activity.getSystemService(DisplayManager::class.java)
        if (!enabled || manager == null) return@DisposableEffect onDispose { }

        var presentation: Presentation? = null
        fun closePanel() {
            val previous = presentation
            presentation = null
            previous?.dismiss()
            bridge?.visibility(false)
        }
        fun reconcile() {
            val hostDisplay = if (Build.VERSION.SDK_INT >= 30) activity.display else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay
            }
            val target = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull {
                it.isValid && it.displayId != hostDisplay?.displayId
            }
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || target == null) {
                closePanel()
                return
            }
            if (presentation?.display?.displayId == target.displayId) return
            closePanel()

            val dialog = Presentation(activity, target)
            val view = ComposeView(dialog.context).apply {
                setViewTreeLifecycleOwner(activity)
                setViewTreeViewModelStoreOwner(activity)
                setViewTreeSavedStateRegistryOwner(activity)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { content() }
            }
            dialog.setContentView(view)
            dialog.window?.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            dialog.setOnDismissListener {
                view.disposeComposition()
                if (presentation === dialog) {
                    presentation = null
                    bridge?.visibility(false)
                }
            }
            try {
                dialog.show()
                dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                presentation = dialog
                bridge?.visibility(true)
            } catch (_: WindowManager.InvalidDisplayException) {
                view.disposeComposition()
            }
        }

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = reconcile()
            override fun onDisplayRemoved(displayId: Int) = reconcile()
            override fun onDisplayChanged(displayId: Int) = reconcile()
        }
        val lifecycleObserver = LifecycleEventObserver { _, _ -> reconcile() }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        activity.lifecycle.addObserver(lifecycleObserver)
        reconcile()
        onDispose {
            manager.unregisterDisplayListener(listener)
            activity.lifecycle.removeObserver(lifecycleObserver)
            closePanel()
        }
    }
}

@Composable
private fun DualScreenPanel(
    title: String,
    bridge: DualScreenBridge?,
    hud: GameHudSettings,
    allowGameInput: Boolean,
    allowMenus: Boolean,
    onHud: (GameHudSettings) -> Unit,
    onHotbar: (Int) -> Unit
) {
    var page by remember { mutableStateOf(hud.panelHome) }
    var returnPage by remember { mutableStateOf<PanelPage?>(null) }
    var inventory by remember(bridge) { mutableStateOf<PanelInventory?>(null) }
    var pixels by remember(bridge) { mutableStateOf<IntArray?>(null) }
    var click by remember { mutableStateOf(PanelCommand.PICK) }

    LaunchedEffect(bridge) {
        if (bridge == null) return@LaunchedEffect
        while (true) {
            inventory = withContext(Dispatchers.IO) { bridge.readInventory() }
            delay(120L)
        }
    }

    val live = inventory
    val table = live?.layout == PanelLayout.CRAFTING_TABLE
    val tableSession = live?.generation?.takeIf { hud.routeCrafting && table }
    LaunchedEffect(tableSession) {
        if (tableSession != null) {
            if (page != PanelPage.CRAFT) returnPage = page
            page = PanelPage.CRAFT
            click = PanelCommand.PICK
        } else {
            if (page == PanelPage.CRAFT) returnPage?.let { page = it }
            returnPage = null
        }
    }
    val routed = tableSession?.takeIf { page == PanelPage.CRAFT && allowMenus && live?.interactive == true }
    DisposableEffect(bridge, routed) {
        bridge?.routeCrafting(routed)
        onDispose { if (routed != null) bridge?.routeCrafting(null) }
    }

    val revision = live?.mapRevision ?: -1
    LaunchedEffect(bridge, page, hud.panelMap, revision) {
        pixels = null
        if (bridge != null && page == PanelPage.MAP && hud.panelMap == PanelMapSource.MINECRAFT && revision >= 0) {
            pixels = withContext(Dispatchers.IO) { bridge.readMap(revision) }
        }
    }

    val interactive = live?.interactive == true && if (table) routed != null else allowGameInput
    val sendSlot: (Int) -> Unit = { slot ->
        if (interactive && live != null) bridge?.send(live, click, slot)
    }

    Column(
        Modifier.fillMaxSize().background(Prissma.colors.surfaceVoid).safeDrawingPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = Prissma.colors.textPrimary, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelPage.entries.forEach { tab ->
                FilterChip(selected = page == tab, onClick = { page = tab; click = PanelCommand.PICK },
                    label = { Text(tab.label) })
            }
        }
        HorizontalDivider(color = Prissma.colors.borderSubtle)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when {
                page == PanelPage.OPTIONS -> {
                    GameHudControls(hud, onHud)
                    if (bridge != null) PanelPreferenceControls(hud, onHud)
                    OutlinedButton(
                        onClick = { onHud(hud.copy(dualScreen = false)) },
                        enabled = (live?.held?.count ?: 0) == 0
                    ) { Text("Desactivar doble pantalla") }
                    if ((live?.held?.count ?: 0) > 0) {
                        Text("Guarda el objeto que llevas en el cursor antes de cerrar.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                bridge == null -> {
                    Text("El inventario, el crafteo y el mapa requieren Java 1.21.4 con Fabric.",
                        style = MaterialTheme.typography.bodyMedium)
                    if (page == PanelPage.INVENTORY) SlotRow((0..8).toList(), null, allowGameInput,
                        "Acceso rápido", onSlot = onHotbar)
                }
                live == null -> Text("Entra a un mundo para conectar el panel.", style = MaterialTheme.typography.bodyMedium)
                page == PanelPage.MAP -> {
                    Text("X ${live.x}    Y ${live.y}    Z ${live.z}", style = MaterialTheme.typography.titleSmall)
                    val image = remember(pixels) {
                        pixels?.let { Bitmap.createBitmap(it, DUAL_MAP_SIZE, DUAL_MAP_SIZE, Bitmap.Config.ARGB_8888).asImageBitmap() }
                    }
                    if (hud.panelMap == PanelMapSource.XAERO) {
                        XaeroMapPanel(live, interactive && !table) {
                            bridge.send(live, PanelCommand.OPEN_XAERO)
                        }
                    } else if (image == null) Text("Equipa un mapa explorado en una mano para verlo aquí.")
                    else Image(image, contentDescription = "Mapa equipado",
                        modifier = Modifier.size(192.dp), contentScale = ContentScale.Fit)
                }
                table && (!hud.routeCrafting || page != PanelPage.CRAFT) -> {
                    Text("Mesa de crafteo abierta", style = MaterialTheme.typography.titleSmall)
                    if (hud.routeCrafting) OutlinedButton(onClick = { page = PanelPage.CRAFT }) { Text("Mostrar mesa aquí") }
                    else Text("Usa la mesa en la pantalla del juego.")
                }
                else -> {
                    if (!interactive) Text("Vuelve a la vista de juego para mover objetos.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(click == PanelCommand.SPLIT, {
                            click = if (click == PanelCommand.SPLIT) PanelCommand.PICK else PanelCommand.SPLIT
                        }, label = { Text("Dividir / colocar uno") }, enabled = interactive)
                        FilterChip(click == PanelCommand.QUICK_MOVE, {
                            click = if (click == PanelCommand.QUICK_MOVE) PanelCommand.PICK else PanelCommand.QUICK_MOVE
                        }, label = { Text("Mover rápido") }, enabled = interactive)
                        if (live.held.count > 0) Text("En cursor · ${live.held.name} ×${live.held.count}")
                    }
                    if (page == PanelPage.CRAFT) {
                        if (table) OutlinedButton(
                            onClick = { bridge.send(live, PanelCommand.CLOSE_TABLE) },
                            enabled = interactive
                        ) { Text("Cerrar mesa") }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                val side = live.layout.craftSize
                                repeat(side) { row ->
                                    val first = row * side + 1
                                    SlotRow((first until first + side).toList(), live, interactive,
                                        label = if (row == 0) "Crafteo ${side}×$side" else null,
                                        compact = hud.compactPanel, onSlot = sendSlot)
                                }
                            }
                            SlotRow(listOf(0), live, interactive, "Resultado", compact = hud.compactPanel, onSlot = sendSlot)
                        }
                    } else {
                        SlotRow(listOf(5, 6, 7, 8, 45), live, interactive, "Armadura y mano secundaria",
                            compact = hud.compactPanel, onSlot = sendSlot)
                    }
                    val hotbarStart = live.layout.hotbarStart
                    val inventoryStart = live.layout.inventoryStart
                    if (page == PanelPage.INVENTORY) {
                        SlotRow((hotbarStart until hotbarStart + 9).toList(), live, interactive,
                            "Barra rápida", live.selectedSlot + hotbarStart, hud.compactPanel, sendSlot)
                    }
                    for (start in inventoryStart until hotbarStart step 9) {
                        SlotRow((start until start + 9).toList(), live, interactive,
                            if (start == inventoryStart) "Inventario" else null, compact = hud.compactPanel, onSlot = sendSlot)
                    }
                    if (page == PanelPage.CRAFT) {
                        SlotRow((hotbarStart until hotbarStart + 9).toList(), live, interactive,
                            "Barra rápida", live.selectedSlot + hotbarStart, hud.compactPanel, sendSlot)
                    }
                }
            }
        }

        if (live != null && !table && page == PanelPage.INVENTORY) {
            SlotRow((0..8).toList(), null, interactive, "Seleccionar en mano", live.selectedSlot) { slot ->
                if (interactive) bridge?.send(live, PanelCommand.SELECT_HOTBAR, slot)
            }
        }
    }
}

@Composable
private fun XaeroMapPanel(live: PanelInventory, canOpen: Boolean, onOpen: () -> Unit) {
    if (live.xaeroWorldMap) {
        OutlinedButton(onClick = onOpen, enabled = canOpen) { Text("Abrir World Map arriba") }
    }
    if (live.xaeroMinimap) {
        Text("Marcadores de Xaero’s Minimap", style = MaterialTheme.typography.titleSmall)
        if (live.waypoints.isEmpty()) Text("El conjunto activo no tiene marcadores visibles.")
        live.waypoints.forEach { mark ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(mark.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("X ${mark.x} · Y ${mark.y} · Z ${mark.z}", color = Prissma.colors.textMuted,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    } else if (!live.xaeroWorldMap) {
        Text("Instala Xaero’s World Map o Xaero’s Minimap para Fabric 1.21.4 desde el Taller.")
    }
}

@Composable
private fun SlotRow(
    slots: List<Int>,
    live: PanelInventory?,
    enabled: Boolean,
    label: String? = null,
    selected: Int? = null,
    compact: Boolean = false,
    onSlot: (Int) -> Unit
) {
    Column {
        if (label != null) Text(label, color = Prissma.colors.textMuted, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            slots.forEach { number ->
                val item = live?.slots?.get(number)
                val name = item?.name?.takeIf { it.isNotBlank() } ?: if (live == null) "${number + 1}" else "—"
                val description = if (item == null) "Ranura $name" else if (item.count == 0) "Ranura vacía" else "$name, ${item.count}"
                val colors = if (number == selected) ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) else ButtonDefaults.filledTonalButtonColors()
                FilledTonalButton(
                    onClick = { onSlot(number) },
                    modifier = Modifier.width(when {
                        live == null -> 46.dp
                        compact -> 53.dp
                        else -> 66.dp
                    })
                        .semantics { contentDescription = description },
                    enabled = enabled,
                    colors = colors,
                    contentPadding = PaddingValues(horizontal = 3.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item != null && item.count > 1) Text("×${item.count}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
