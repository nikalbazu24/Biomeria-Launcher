package com.prissma.launcher.engine.java

import android.app.Presentation
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
        PrissmaTheme { DualScreenPanel(activity, title, bridge, hud, allowGameInput, allowMenus, onHud, onHotbar) }
    })
    DisposableEffect(activity, enabled, bridge) {
        val manager = activity.getSystemService(DisplayManager::class.java)
        if (!enabled || manager == null) return@DisposableEffect onDispose { }

        var presentation: Presentation? = null
        fun closePanel() {
            val previous = presentation
            presentation = null
            activity.setLowerPanelVisible(false)
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
                    activity.setLowerPanelVisible(false)
                    bridge?.visibility(false)
                }
            }
            try {
                dialog.show()
                dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                presentation = dialog
                bridge?.visibility(true)
                activity.setLowerPanelVisible(true)
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
    activity: JavaGameActivity,
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
    var previousMenuPage by remember { mutableStateOf<PanelPage?>(null) }
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
    val menuPage = live?.layout?.menuPage
    val menuSession = live?.takeIf {
        hud.routes(it.layout) && (it.layout != PanelLayout.XAERO || it.xaeroLowerAvailable)
    }?.generation
    var showRecipes by remember(menuSession) { mutableStateOf(false) }
    var recipePadInput by remember(menuSession) { mutableStateOf<((PanelPadAction) -> Unit)?>(null) }
    LaunchedEffect(menuSession, menuPage) {
        if (menuSession != null && menuPage != null) {
            if (previousMenuPage == null) returnPage = page
            previousMenuPage = menuPage
            page = menuPage
            click = PanelCommand.PICK
        } else {
            if (page == previousMenuPage) returnPage?.let { page = it }
            if (previousMenuPage != null) activity.releaseLowerGamepad()
            returnPage = null
            previousMenuPage = null
        }
    }
    val routed = menuSession?.takeIf { page == menuPage && allowMenus && live?.interactive == true }
    DisposableEffect(bridge, routed) {
        bridge?.routeMenu(routed)
        onDispose { if (routed != null) bridge?.routeMenu(null) }
    }

    val revision = live?.mapRevision ?: -1
    LaunchedEffect(bridge, page, hud.panelMap, revision) {
        pixels = null
        if (bridge != null && page == PanelPage.MAP && hud.panelMap == PanelMapSource.MINECRAFT && revision >= 0) {
            pixels = withContext(Dispatchers.IO) { bridge.readMap(revision) }
        }
    }

    val interactive = live?.interactive == true && if (menuPage != null) routed != null else allowGameInput
    val controllerActive = activity.lowerGamepadFocused
    val showingMap = routed != null && live?.layout == PanelLayout.XAERO
    val canShowRecipes = table && routed != null && live?.recipeBookAvailable == true
    val showingRecipes = showRecipes && canShowRecipes
    val padRows = remember(bridge, live?.layout, page, showingRecipes) {
        when {
            showingRecipes || (bridge != null && live == null) -> emptyList()
            else -> panelPadRows(live?.layout, page)
        }
    }
    var padSlot by remember(live?.generation, page) { mutableStateOf<PanelPadSlot?>(null) }
    val currentPadSlot = padSlot?.takeIf { slot -> padRows.any { slot in it } } ?: padRows.firstOrNull()?.firstOrNull()
    val padFocus = currentPadSlot.takeIf { controllerActive }

    LaunchedEffect(controllerActive, menuSession, menuPage) {
        if (controllerActive && menuSession != null && menuPage != null) page = menuPage
    }

    val actionPage = page
    val panelAction by rememberUpdatedState<(PanelPadAction) -> Unit>({ action ->
        val target = padSlot?.takeIf { slot -> padRows.any { slot in it } } ?: padRows.firstOrNull()?.firstOrNull()
        when {
            canShowRecipes && showRecipes -> recipePadInput?.invoke(action)
            action == PanelPadAction.BACK -> {
                if (routed == null || live == null || bridge?.send(live, PanelCommand.CLOSE_MENU) == true) {
                    activity.releaseLowerGamepad()
                }
            }
            page != actionPage -> Unit
            showingRecipes -> Unit
            showingMap && bridge != null && live != null -> when (action) {
                PanelPadAction.SPLIT -> bridge.send(live, PanelCommand.MAP_ZOOM, -1)
                PanelPadAction.TRANSFER -> bridge.send(live, PanelCommand.MAP_ZOOM, 1)
                else -> Unit
            }
            action == PanelPadAction.NEXT_TAB && canShowRecipes -> showRecipes = true
            action == PanelPadAction.NEXT_TAB || action == PanelPadAction.PREVIOUS_TAB -> {
                if (menuPage == null && bridge != null) {
                    val tabs = PanelPage.entries.filter { it.canStart }
                    val index = tabs.indexOf(page)
                    val offset = if (action == PanelPadAction.NEXT_TAB) 1 else -1
                    page = if (index == -1) PanelPage.INVENTORY else tabs[(index + offset + tabs.size) % tabs.size]
                    click = PanelCommand.PICK
                }
            }
            action.directional -> padSlot = movePanelPadSlot(padRows, target, action)
            page == PanelPage.MAP && action == PanelPadAction.PICK && live != null && interactive &&
                hud.panelMap == PanelMapSource.XAERO -> bridge?.send(live, PanelCommand.OPEN_XAERO)
            target != null -> {
                val command = panelPadCommand(action, target)
                if (command != null) {
                    if (live == null && allowGameInput && command == PanelCommand.SELECT_HOTBAR) {
                        onHotbar(target.number)
                    } else if (live != null && interactive) {
                        bridge?.send(live, command, target.number)
                    }
                }
            }
        }
        Unit
    })
    val panelPan by rememberUpdatedState<(Float, Float) -> Unit>({ x, y ->
        if (showingMap && page == actionPage && live != null) {
            bridge?.send(live, PanelCommand.MAP_PAN, xaeroPanVector(x, y))
        }
    })
    DisposableEffect(activity) {
        val detach = activity.attachLowerPanelInput({ panelAction(it) }, { x, y -> panelPan(x, y) })
        onDispose(detach)
    }

    val sendSlot: (Int) -> Unit = { slot ->
        if (controllerActive) padSlot = PanelPadSlot(slot)
        if (interactive && live != null) bridge?.send(live, click, slot)
    }

    Column(
        Modifier.fillMaxSize().background(Prissma.colors.surfaceVoid).safeDrawingPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, color = Prissma.colors.textPrimary, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        val controls = when {
            !controllerActive -> "Mantén Select para usar el mando aquí"
            showingRecipes -> "↑/↓ · Recorrer    A · Elegir    B · Atrás"
            showingMap -> "Stick derecho · Mover mapa    X/Y · Zoom    B · Cerrar"
            padFocus != null && !padFocus.selectsHotbar -> "A · Tomar o colocar    X · Dividir    Y · Mover rápido    B · Volver"
            padFocus?.selectsHotbar == true -> "A · Seleccionar en mano    B · Volver"
            page == PanelPage.MAP && hud.panelMap == PanelMapSource.XAERO -> "A · Abrir Xaero    L1/R1 · Pestañas    B · Volver"
            else -> "L1/R1 · Pestañas    B · Devolver mando"
        }
        Text(controls, color = if (controllerActive) MaterialTheme.colorScheme.primary else Prissma.colors.textMuted,
            style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelPage.entries.filter { it != PanelPage.STORAGE || menuPage == PanelPage.STORAGE }.forEach { tab ->
                FilterChip(selected = page == tab, onClick = { page = tab; click = PanelCommand.PICK },
                    label = { Text(tab.label) })
            }
        }
        HorizontalDivider(color = Prissma.colors.borderSubtle)
        if (showingMap && bridge != null && live != null) {
            XaeroMapPane(bridge, live, Modifier.weight(1f).fillMaxWidth())
            return@Column
        }
        if (showingRecipes && bridge != null && live != null) {
            RecipeBookPane(
                bridge, live, controllerActive, onBack = { showRecipes = false },
                onPlaced = {
                    padSlot = PanelPadSlot(0)
                    showRecipes = false
                },
                onPadInput = { recipePadInput = it },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            return@Column
        }
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
                        "Acceso rápido", padFocus = padFocus, selectsHotbar = true, onSlot = onHotbar)
                }
                live == null -> Text("Entra a un mundo para conectar el panel.", style = MaterialTheme.typography.bodyMedium)
                live.layout == PanelLayout.XAERO -> {
                    Text(if (menuSession != null && page == PanelPage.MAP) "El mapa se abrirá al volver al juego."
                        else "Mapa abierto en la pantalla del juego.")
                    if (menuSession != null && page != PanelPage.MAP) {
                        OutlinedButton(onClick = { page = PanelPage.MAP }) { Text("Mostrar mapa aquí") }
                    }
                }
                page == PanelPage.MAP -> {
                    Text("X ${live.x}    Y ${live.y}    Z ${live.z}", style = MaterialTheme.typography.titleSmall)
                    val image = remember(pixels) {
                        pixels?.let { Bitmap.createBitmap(it, DUAL_MAP_SIZE, DUAL_MAP_SIZE, Bitmap.Config.ARGB_8888).asImageBitmap() }
                    }
                    if (hud.panelMap == PanelMapSource.XAERO) {
                        XaeroMapPanel(live, interactive && menuPage == null, hud.routeXaeroMap) {
                            bridge.send(live, PanelCommand.OPEN_XAERO)
                        }
                    } else if (image == null) Text("Equipa un mapa explorado en una mano para verlo aquí.")
                    else Image(image, contentDescription = "Mapa equipado",
                        modifier = Modifier.size(192.dp), contentScale = ContentScale.Fit)
                }
                menuPage != null && (menuSession == null || page != menuPage) -> {
                    Text(live.menuTitle, style = MaterialTheme.typography.titleSmall)
                    if (menuSession != null) OutlinedButton(onClick = { page = menuPage }) { Text("Mostrar aquí") }
                    else Text("Usa el menú en la pantalla del juego.")
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
                    if (menuPage == PanelPage.STORAGE) {
                        ContainerSlots(live, interactive, hud.compactPanel, padFocus, sendSlot) {
                            bridge.send(live, PanelCommand.CLOSE_MENU)
                        }
                    } else if (page == PanelPage.CRAFT) {
                        if (table) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { bridge.send(live, PanelCommand.CLOSE_MENU) },
                                enabled = interactive) { Text("Cerrar mesa") }
                            if (live.recipeBookAvailable) OutlinedButton(
                                onClick = { showRecipes = true }, enabled = interactive
                            ) { Text(if (controllerActive) "Recetas [R1]" else "Recetas") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                val side = live.layout.craftSize
                                repeat(side) { row ->
                                    val first = row * side + 1
                                    SlotRow((first until first + side).toList(), live, interactive,
                                        label = if (row == 0) "Crafteo ${side}×$side" else null,
                                        compact = hud.compactPanel, padFocus = padFocus, onSlot = sendSlot)
                                }
                            }
                            SlotRow(listOf(0), live, interactive, "Resultado", compact = hud.compactPanel,
                                padFocus = padFocus, onSlot = sendSlot)
                        }
                    } else {
                        SlotRow(listOf(5, 6, 7, 8, 45), live, interactive, "Armadura y mano secundaria",
                            compact = hud.compactPanel, padFocus = padFocus, onSlot = sendSlot)
                    }
                    val hotbarStart = live.layout.hotbarStart
                    val inventoryStart = live.layout.inventoryStart
                    if (page == PanelPage.INVENTORY) {
                        SlotRow((hotbarStart until hotbarStart + 9).toList(), live, interactive,
                            "Barra rápida", live.selectedSlot + hotbarStart, hud.compactPanel, padFocus = padFocus, onSlot = sendSlot)
                    }
                    for (start in inventoryStart until hotbarStart step 9) {
                        SlotRow((start until start + 9).toList(), live, interactive,
                            if (start == inventoryStart) "Inventario" else null, compact = hud.compactPanel,
                            fit = menuPage == PanelPage.STORAGE, padFocus = padFocus, onSlot = sendSlot)
                    }
                    if (page == PanelPage.CRAFT || menuPage == PanelPage.STORAGE) {
                        SlotRow((hotbarStart until hotbarStart + 9).toList(), live, interactive,
                            "Barra rápida", live.selectedSlot + hotbarStart, hud.compactPanel,
                            fit = menuPage == PanelPage.STORAGE, padFocus = padFocus, onSlot = sendSlot)
                    }
                }
            }
        }

        if (live != null && live.layout == PanelLayout.PERSONAL && page == PanelPage.INVENTORY) {
            SlotRow((0..8).toList(), null, interactive, "Seleccionar en mano", live.selectedSlot,
                padFocus = padFocus, selectsHotbar = true) { slot ->
                if (controllerActive) padSlot = PanelPadSlot(slot, selectsHotbar = true)
                if (interactive) bridge?.send(live, PanelCommand.SELECT_HOTBAR, slot)
            }
        }
    }
}

@Composable
private fun ContainerSlots(
    live: PanelInventory,
    interactive: Boolean,
    compact: Boolean,
    padFocus: PanelPadSlot?,
    onSlot: (Int) -> Unit,
    onClose: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(live.menuTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedButton(onClick = onClose, enabled = interactive) { Text("Cerrar") }
    }
    val furnace = live.furnace
    if (furnace != null) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SlotRow(listOf(0), live, interactive, "Material", compact = compact, padFocus = padFocus, onSlot = onSlot)
            SlotRow(listOf(1), live, interactive, "Combustible", compact = compact, padFocus = padFocus, onSlot = onSlot)
            SlotRow(listOf(2), live, interactive, "Resultado", compact = compact, padFocus = padFocus, onSlot = onSlot)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FurnaceMeter("Cocción", furnace.cook, Modifier.weight(1f))
            FurnaceMeter("Combustible", if (furnace.burning) furnace.fuel else 0f, Modifier.weight(1f))
        }
    } else {
        repeat(live.layout.chestRows) { row ->
            SlotRow((row * 9 until (row + 1) * 9).toList(), live, interactive, fit = true, padFocus = padFocus, onSlot = onSlot)
        }
    }
}

@Composable
private fun FurnaceMeter(label: String, progress: Float, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$label · ${(progress * 100).toInt()} %", style = MaterialTheme.typography.labelMedium)
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun XaeroMapPanel(live: PanelInventory, canOpen: Boolean, preferBelow: Boolean, onOpen: () -> Unit) {
    if (live.xaeroWorldMap) {
        val target = if (preferBelow && live.xaeroLowerAvailable) "abajo" else "arriba"
        OutlinedButton(onClick = onOpen, enabled = canOpen) { Text("Abrir World Map $target") }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SlotRow(
    slots: List<Int>,
    live: PanelInventory?,
    enabled: Boolean,
    label: String? = null,
    selected: Int? = null,
    compact: Boolean = false,
    fit: Boolean = false,
    padFocus: PanelPadSlot? = null,
    selectsHotbar: Boolean = false,
    onSlot: (Int) -> Unit
) {
    Column(if (fit) Modifier.fillMaxWidth() else Modifier) {
        if (label != null) Text(label, color = Prissma.colors.textMuted, style = MaterialTheme.typography.labelMedium)
        Row(if (fit) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            slots.forEach { number ->
                val padSelected = padFocus == PanelPadSlot(number, selectsHotbar)
                val bringIntoView = remember { BringIntoViewRequester() }
                LaunchedEffect(padSelected) {
                    if (padSelected) bringIntoView.bringIntoView()
                }
                val item = live?.slots?.get(number)
                val name = item?.name?.takeIf { it.isNotBlank() } ?: if (live == null) "${number + 1}" else "—"
                val description = if (item == null) "Ranura $name" else if (item.count == 0) "Ranura vacía" else "$name, ${item.count}"
                val colors = if (number == selected) ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) else ButtonDefaults.filledTonalButtonColors()
                FilledTonalButton(
                    onClick = { onSlot(number) },
                    modifier = (if (fit) Modifier.weight(1f) else Modifier.width(when {
                        live == null -> 46.dp
                        compact -> 53.dp
                        else -> 66.dp
                    }))
                        .bringIntoViewRequester(bringIntoView)
                        .semantics { contentDescription = description },
                    enabled = enabled,
                    colors = colors,
                    border = if (padSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
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
