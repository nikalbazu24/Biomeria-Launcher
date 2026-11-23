package com.prissma.launcher.engine.java

import android.app.Presentation
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.prissma.launcher.ui.components.BlockButton
import com.prissma.launcher.ui.components.BlockButtons
import com.prissma.launcher.ui.components.LauncherButton
import com.prissma.launcher.ui.theme.PixelFont
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.draw.alpha
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
    fps: Int?,
    allowGameInput: Boolean,
    allowMenus: Boolean,
    onHud: (GameHudSettings) -> Unit,
    onHotbar: (Int) -> Unit
) {
    val content by rememberUpdatedState<@Composable () -> Unit>({
        PrissmaTheme { DualScreenPanel(activity, title, bridge, hud, fps, allowGameInput, allowMenus, onHud, onHotbar) }
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
    fps: Int?,
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
        val history = PanelSnapshotHistory()
        while (true) {
            val snapshot = withContext(Dispatchers.IO) { bridge.readSnapshot() }
            inventory = history.update(snapshot, SystemClock.elapsedRealtime())
            delay(120L)
        }
    }
    val menuPolicy = if (!allowMenus) 0 else PanelLayout.entries
        .filter { it.menuPage != null && hud.routes(it) }
        .fold(0) { mask, layout -> mask or (1 shl layout.ordinal) }
    LaunchedEffect(bridge, menuPolicy) { bridge?.prepareMenus(menuPolicy) }

    val live = inventory
    val table = live?.layout == PanelLayout.CRAFTING_TABLE
    val menuPage = live?.layout?.menuPage
    val menuSession = live?.takeIf {
        hud.routes(it.layout)
    }?.generation
    var showRecipes by remember(menuSession) { mutableStateOf(false) }
    var customizing by remember { mutableStateOf(false) }
    var characterPadInput by remember { mutableStateOf<((PanelPadAction) -> Unit)?>(null) }
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
    LaunchedEffect(bridge, routed) { bridge?.routeMenu(routed) }

    val revision = live?.mapRevision ?: -1
    LaunchedEffect(bridge, page, hud.panelMap, revision) {
        pixels = null
        if (bridge != null && page == PanelPage.MAP && hud.panelMap == PanelMapSource.MINECRAFT && revision >= 0) {
            pixels = withContext(Dispatchers.IO) { bridge.readMap(revision) }
        }
    }

    val interactive = live?.interactive == true && if (menuPage != null) routed != null else allowGameInput
    val controllerActive = activity.lowerGamepadFocused
    val showingMap = bridge != null && page == PanelPage.MAP && hud.panelMap == PanelMapSource.PRISSMA
    var mapPadInput by remember { mutableStateOf<TerrainPadInput?>(null) }
    val searchable = live != null && interactive &&
        (page == PanelPage.INVENTORY || page == PanelPage.STORAGE && live.layout.chestRows > 0)
    var editingSearch by remember(live?.generation, page) { mutableStateOf(false) }
    var slotQuery by remember(live?.generation, page) { mutableStateOf("") }
    var searchKey by remember(live?.generation, page) { mutableStateOf(PanelPadSlot(0)) }
    val canShowRecipes = table && routed != null && live?.recipeBookAvailable == true
    val showingRecipes = showRecipes && canShowRecipes
    val unfilteredRows = remember(bridge, live?.layout, page, showingRecipes, customizing) {
        when {
            showingRecipes || customizing || (bridge != null && live == null) -> emptyList()
            else -> panelPadRows(live?.layout, page)
        }
    }
    val matches = remember(unfilteredRows, live?.slots, slotQuery, searchable) {
        if (searchable && slotQuery.isNotBlank()) {
            filterPanelSlotRows(unfilteredRows, live?.slots.orEmpty(), slotQuery)
        } else null
    }
    val padRows = matches ?: unfilteredRows
    val matchingSlots = remember(matches) { matches?.flatten()?.map(PanelPadSlot::number)?.toSet() }
    var padSlot by remember(live?.generation, page) { mutableStateOf<PanelPadSlot?>(null) }
    val currentPadSlot = padSlot?.takeIf { slot -> padRows.any { slot in it } } ?: padRows.firstOrNull()?.firstOrNull()
    val padFocus = currentPadSlot.takeIf { controllerActive }

    LaunchedEffect(controllerActive, menuSession, menuPage) {
        if (controllerActive && menuSession != null && menuPage != null) page = menuPage
    }

    val actionPage = page
    fun applySearchKey(key: Int) {
        when (key) {
            SEARCH_SPACE -> if (slotQuery.length < 48) slotQuery += " "
            SEARCH_ERASE -> slotQuery = slotQuery.dropLast(1)
            SEARCH_DONE -> editingSearch = false
            else -> if (slotQuery.length < 48) slotQuery += inventorySearchCharacters[key]
        }
    }
    val panelAction by rememberUpdatedState<(PanelPadAction) -> Unit>({ action ->
        val target = padSlot?.takeIf { slot -> padRows.any { slot in it } } ?: padRows.firstOrNull()?.firstOrNull()
        when {
            showingMap && page == actionPage && mapPadInput?.managingWaypoints?.invoke() == true ->
                mapPadInput?.action?.invoke(action)
            canShowRecipes && showRecipes -> recipePadInput?.invoke(action)
            customizing -> characterPadInput?.invoke(action)
            action == PanelPadAction.MARK && page == PanelPage.INVENTORY && live?.layout == PanelLayout.PERSONAL &&
                bridge != null -> customizing = true
            editingSearch && searchable && page == actionPage -> when {
                action == PanelPadAction.BACK || action == PanelPadAction.SEARCH -> editingSearch = false
                action == PanelPadAction.PICK -> applySearchKey(searchKey.number)
                action.directional -> searchKey = movePanelPadSlot(inventorySearchRows, searchKey, action) ?: searchKey
                else -> Unit
            }
            action == PanelPadAction.BACK && searchable && slotQuery.isNotBlank() -> slotQuery = ""
            action == PanelPadAction.BACK -> {
                if (routed == null || live == null || bridge?.send(live, PanelCommand.CLOSE_MENU) == true) {
                    activity.releaseLowerGamepad()
                }
            }
            page != actionPage -> Unit
            showingRecipes -> Unit
            action == PanelPadAction.SEARCH && searchable -> editingSearch = true
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
            showingMap -> mapPadInput?.action?.invoke(action)
            action.directional -> padSlot = movePanelPadSlot(padRows, target, action)
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
        if (showingMap && page == actionPage) mapPadInput?.pan?.invoke(x, y)
    })
    DisposableEffect(activity) {
        val detach = activity.attachLowerPanelInput({ panelAction(it) }, { x, y -> panelPan(x, y) })
        onDispose(detach)
    }

    val sendSlot: (Int) -> Unit = { slot ->
        if (controllerActive) padSlot = PanelPadSlot(slot)
        if (interactive && live != null && (matchingSlots == null || slot in matchingSlots)) bridge?.send(live, click, slot)
    }

    val hints: List<Pair<String, String>> = when {
        !controllerActive -> emptyList()
        showingRecipes -> listOf("↕" to "Recorrer", "A" to "Elegir", "B" to "Atrás")
        customizing -> listOf("✛" to "Recorrer", "A" to "Elegir", "B" to "Listo")
        editingSearch && searchable -> listOf("✛" to "Teclado", "A" to "Elegir", "B" to "Aplicar")
        showingMap -> listOf("R" to "Mover", "X" to "Alejar", "Y" to "Acercar", "A" to "Centrar", "R3" to "Nueva marca", "Start" to "Marcas")
        matchingSlots != null -> listOf("✛" to "Coincidencias", "A" to "Tomar o colocar", "B" to "Limpiar")
        searchable -> listOfNotNull("A" to "Tomar o colocar", "X" to "Dividir", "Y" to "Mover rápido", "Start" to "Buscar",
            ("R3" to "Personaje").takeIf { page == PanelPage.INVENTORY && live?.layout == PanelLayout.PERSONAL },
            "B" to "Devolver mando")
        padFocus != null && !padFocus.selectsHotbar -> listOf("A" to "Tomar o colocar", "X" to "Dividir", "Y" to "Mover rápido", "B" to "Volver")
        padFocus?.selectsHotbar == true -> listOf("A" to "Llevar en la mano", "B" to "Volver")
        else -> listOf("LB/RB" to "Pestañas", "B" to "Devolver mando")
    }
    val latestLive by rememberUpdatedState(live)
    val moveStack = remember(bridge) {
        { from: Int, to: Int ->
            val now = latestLive
            if (now != null && bridge != null) {
                bridge.send(now, PanelCommand.PICK, from)
                bridge.send(now, PanelCommand.PICK, to)
            }
        }
    }
    val dragState = remember(bridge) { SlotDrag(moveStack) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    CompositionLocalProvider(LocalSlotDrag provides dragState) {
    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInRoot() }) {
    Column(Modifier.fillMaxSize().background(Prissma.colors.surfaceVoid).safeDrawingPadding()) {
        Column(
            Modifier.fillMaxWidth().background(Prissma.colors.ink)
                .drawBehind { drawRect(Prissma.colors.surfaceElevated, Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx())) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PanelKeyChip("LB")
                PanelPage.entries.filter { it != PanelPage.STORAGE || menuPage == PanelPage.STORAGE }.forEach { tab ->
                    BlockButton({ page = tab; click = PanelCommand.PICK }, Modifier.heightIn(min = 52.dp),
                        if (page == tab) BlockButtons.primary else BlockButtons.neutral,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(if (tab == PanelPage.STORAGE) live?.menuTitle?.takeIf { it.isNotBlank() } ?: tab.label else tab.label,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                }
                PanelKeyChip("RB")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, Modifier.weight(1f), color = Prissma.colors.textMuted, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hud.widgetsBelow) GameWidgets(hud, fps, bridge)
                else if (live != null) Text("X ${live.x}  Y ${live.y}  Z ${live.z}", color = Prissma.colors.textPrimary,
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
        if (editingSearch && searchable) {
            InventorySearchKeyboard(slotQuery, searchKey.takeIf { controllerActive }, ::applySearchKey,
                { slotQuery = "" }, Modifier.weight(1f))
            return@Column
        }
        if (showingMap && bridge != null) {
            TerrainMapPane(bridge, Modifier.weight(1f).fillMaxWidth(), controllerActive = controllerActive,
                onPadInput = { mapPadInput = it })
            return@Column
        }
        if (customizing && bridge != null) {
            CharacterEditor(bridge, onUpload = { activity.pickSkinImage() }, onDone = { customizing = false },
                modifier = Modifier.weight(1f).fillMaxWidth(), controllerActive = controllerActive,
                onPadInput = { characterPadInput = it })
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
                page == PanelPage.MAP -> {
                    Text("X ${live.x}    Y ${live.y}    Z ${live.z}", style = MaterialTheme.typography.titleSmall)
                    val image = remember(pixels) {
                        pixels?.let { Bitmap.createBitmap(it, DUAL_MAP_SIZE, DUAL_MAP_SIZE, Bitmap.Config.ARGB_8888).asImageBitmap() }
                    }
                    if (image == null) Text("Equipa un mapa explorado en una mano para verlo aquí.")
                    else Image(image, contentDescription = "Mapa equipado",
                        modifier = Modifier.size(192.dp), contentScale = ContentScale.Fit)
                }
                menuPage != null && (menuSession == null || page != menuPage) -> {
                    Text(live.menuTitle, style = MaterialTheme.typography.titleSmall)
                    if (menuSession != null) LauncherButton("Mostrar aquí", { page = menuPage }, primary = true)
                    else Text("Usa el menú en la pantalla del juego.")
                }
                else -> {
                    if (!interactive) Text("Vuelve a la vista de juego para mover objetos.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (searchable) {
                            FilterChip(slotQuery.isNotBlank(), { editingSearch = true }, label = {
                                Text(if (slotQuery.isBlank()) "Buscar objetos" else "$slotQuery · ${matchingSlots?.size ?: 0}")
                            })
                            if (matchingSlots != null) TextButton(onClick = { slotQuery = "" }) { Text("Limpiar") }
                        }
                        FilterChip(click == PanelCommand.SPLIT, {
                            click = if (click == PanelCommand.SPLIT) PanelCommand.PICK else PanelCommand.SPLIT
                        }, label = { Text("Dividir / colocar uno") }, enabled = interactive)
                        FilterChip(click == PanelCommand.QUICK_MOVE, {
                            click = if (click == PanelCommand.QUICK_MOVE) PanelCommand.PICK else PanelCommand.QUICK_MOVE
                        }, label = { Text("Mover rápido") }, enabled = interactive)
                        if (live.held.count > 0) Text("En cursor · ${live.held.name} ×${live.held.count}")
                    }
                    if (menuPage == PanelPage.STORAGE) {
                        ContainerSlots(live, interactive, hud.compactPanel, padFocus, matchingSlots, sendSlot) {
                            bridge.send(live, PanelCommand.CLOSE_MENU)
                        }
                    } else if (page == PanelPage.CRAFT) {
                        if (table) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LauncherButton("Cerrar mesa", { bridge.send(live, PanelCommand.CLOSE_MENU) }, enabled = interactive)
                            if (live.recipeBookAvailable) LauncherButton(if (controllerActive) "Recetas [R1]" else "Recetas",
                                { showRecipes = true }, primary = true, enabled = interactive)
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
                    } else if (page == PanelPage.INVENTORY && live.layout == PanelLayout.PERSONAL && matchingSlots == null) {
                        CharacterCard(bridge, armor = {
                            for (slot in 5..8) SlotRow(listOf(slot), live, interactive, compact = true, padFocus = padFocus,
                                onSlot = sendSlot)
                        }, offHand = {
                            SlotRow(listOf(45), live, interactive, compact = true, padFocus = padFocus, onSlot = sendSlot)
                        }, onCustomize = { customizing = true })
                    } else {
                        SlotRow(listOf(5, 6, 7, 8, 45), live, interactive, "Armadura y mano secundaria",
                            compact = hud.compactPanel, padFocus = padFocus, matchingSlots = matchingSlots, onSlot = sendSlot)
                    }
                    val hotbarStart = live.layout.hotbarStart
                    val inventoryStart = live.layout.inventoryStart
                    if (page == PanelPage.INVENTORY) {
                        SlotRow((hotbarStart until hotbarStart + 9).toList(), live, interactive,
                            "Barra rápida", live.selectedSlot + hotbarStart, hud.compactPanel, padFocus = padFocus,
                            matchingSlots = matchingSlots, onSlot = sendSlot)
                    }
                    for (start in inventoryStart until hotbarStart step 9) {
                        SlotRow((start until start + 9).toList(), live, interactive,
                            if (start == inventoryStart) "Inventario" else null, compact = hud.compactPanel,
                            fit = menuPage == PanelPage.STORAGE, padFocus = padFocus,
                            matchingSlots = matchingSlots, onSlot = sendSlot)
                    }
                    if (page == PanelPage.CRAFT || menuPage == PanelPage.STORAGE) {
                        SlotRow((hotbarStart until hotbarStart + 9).toList(), live, interactive,
                            "Barra rápida", live.selectedSlot + hotbarStart, hud.compactPanel,
                            fit = menuPage == PanelPage.STORAGE, padFocus = padFocus,
                            matchingSlots = matchingSlots, onSlot = sendSlot)
                    }
                }
            }
        }

        if (live != null && live.layout == PanelLayout.PERSONAL && page == PanelPage.INVENTORY && matchingSlots == null) {
            SlotRow((0..8).toList(), null, interactive, "Seleccionar en mano", live.selectedSlot,
                padFocus = padFocus, selectsHotbar = true) { slot ->
                if (controllerActive) padSlot = PanelPadSlot(slot, selectsHotbar = true)
                if (interactive) bridge?.send(live, PanelCommand.SELECT_HOTBAR, slot)
            }
        }
        }
        PanelFooter(hints, controllerActive) { activity.releaseLowerGamepad() }
    }
    // The stack follows the finger while it is being dragged.
    val finger = dragState.finger
    val icon = dragState.icon
    if (finger != null && dragState.from != null) {
        val size = 64.dp
        val half = with(androidx.compose.ui.platform.LocalDensity.current) { size.toPx() / 2 }
        Box(Modifier.offset { IntOffset((finger.x - rootOrigin.x - half).roundToInt(), (finger.y - rootOrigin.y - half - 48.dp.toPx()).roundToInt()) }
            .size(size).background(Color(0xCC1A2026), RoundedCornerShape(4.dp))
            .border(3.dp, Prissma.colors.gold, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, null, Modifier.fillMaxSize(.8f), filterQuality = FilterQuality.None)
        }
    }
    }
    }
}

/** Small key label for the shoulder buttons on either side of the tabs. */
@Composable
private fun PanelKeyChip(key: String) {
    Text(key, Modifier.background(Prissma.colors.borderSubtle, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
        color = Prissma.colors.textPrimary, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
}

/**
 * Bottom bar: which screen the controller drives and what each button does right now.
 * Touch can hand the controller back to the game; taking it here is done by holding Select.
 */
@Composable
private fun PanelFooter(hints: List<Pair<String, String>>, controllerActive: Boolean, onRelease: () -> Unit) {
    val palette = Prissma.colors
    Row(
        Modifier.fillMaxWidth().background(palette.ink)
            .drawBehind { drawRect(palette.surfaceElevated, size = Size(size.width, 3.dp.toPx())) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BlockButton(onRelease, Modifier.heightIn(min = 52.dp), if (controllerActive) BlockButtons.gold else BlockButtons.neutral,
            enabled = controllerActive, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            Column {
                Text(if (controllerActive) "El mando controla este panel" else "El mando controla el juego",
                    fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text(if (controllerActive) "Toca o pulsa B para devolverlo" else "Mantén Select para usarlo aquí",
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            hints.forEach { (key, label) -> PanelHint(key, label) }
        }
    }
}

@Composable
private fun PanelHint(key: String, label: String) {
    val (back, front) = when (key) {
        "A" -> Color(0xFF7BC74D) to Color(0xFF0E1A08)
        "B" -> Color(0xFFF07D4A) to Color(0xFF2B0E05)
        "X" -> Color(0xFF4FB3D9) to Color(0xFF08202B)
        "Y" -> Color(0xFFF5E663) to Color(0xFF2A2508)
        else -> Color(0xFF34404A) to Color(0xFFF2F5EF)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.heightIn(min = 26.dp).widthIn(min = 26.dp).background(back, RoundedCornerShape(13.dp)).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center) {
            Text(key, color = front, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium)
        }
        Text(label, color = Prissma.colors.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
            maxLines = 1)
    }
}

@Composable
private fun ContainerSlots(
    live: PanelInventory,
    interactive: Boolean,
    compact: Boolean,
    padFocus: PanelPadSlot?,
    matchingSlots: Set<Int>?,
    onSlot: (Int) -> Unit,
    onClose: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(live.menuTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        LauncherButton("Cerrar", onClose, enabled = interactive)
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
        val columns = live.layout.containerColumns
        repeat(live.layout.chestRows) { row ->
            SlotRow((row * columns until (row + 1) * columns).toList(), live, interactive, fit = columns == 9,
                padFocus = padFocus, matchingSlots = matchingSlots, onSlot = onSlot)
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

@OptIn(ExperimentalFoundationApi::class)
/**
 * Finger drag between slots: lift the stack where the finger starts, drop it where it ends.
 * It sends the same two "pick" clicks as tapping the source and then the target.
 */
internal class SlotDrag(val onMove: (from: Int, to: Int) -> Unit) {
    val bounds = mutableStateMapOf<Int, Rect>()
    var from by mutableStateOf<Int?>(null)
    var finger by mutableStateOf<Offset?>(null)
    var icon by mutableStateOf<ImageBitmap?>(null)

    fun slotAt(point: Offset): Int? = bounds.entries.firstOrNull { point in it.value }?.key
}

internal val LocalSlotDrag = compositionLocalOf<SlotDrag?> { null }

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
    matchingSlots: Set<Int>? = null,
    onSlot: (Int) -> Unit
) {
    Column(if (fit) Modifier.fillMaxWidth() else Modifier) {
        if (label != null) Text(label, Modifier.padding(bottom = 4.dp), color = Color(0xFFC3CDD2),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
        Row(if (fit) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            slots.forEach { number ->
                val padSelected = padFocus == PanelPadSlot(number, selectsHotbar)
                val matches = matchingSlots == null || number in matchingSlots
                val bringIntoView = remember { BringIntoViewRequester() }
                LaunchedEffect(padSelected) {
                    if (padSelected) bringIntoView.bringIntoView()
                }
                val item = live?.slots?.get(number)
                val drag = LocalSlotDrag.current
                var origin by remember { mutableStateOf(Offset.Zero) }
                val dragIcon = item?.takeIf { it.count > 0 }?.let { rememberItemIcon(it.id) }
                val name = item?.name?.takeIf { it.isNotBlank() } ?: if (live == null) "${number + 1}" else "—"
                val description = if (item == null) "Ranura $name" else if (item.count == 0) "Ranura vacía" else "$name, ${item.count}"
                val palette = Prissma.colors
                val outline = when {
                    padSelected -> palette.gold
                    number == selected -> palette.textPrimary
                    matchingSlots != null && matches -> palette.brandAccent
                    else -> palette.ink
                }
                Box(
                    (if (fit) Modifier.weight(1f) else Modifier.width(when {
                        live == null -> 52.dp
                        compact -> 56.dp
                        else -> 70.dp
                    }))
                        .aspectRatio(1f)
                        .bringIntoViewRequester(bringIntoView)
                        .onGloballyPositioned { drag?.bounds?.set(number, it.boundsInRoot()); origin = it.positionInRoot() }
                        .alpha(if (matches) 1f else .3f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (number == selected) Color(0xFF2A333B) else Color(0xFF1A2026))
                        .border(3.dp, Color(0xFF232B33), RoundedCornerShape(3.dp))
                        .border(if (padSelected) 4.dp else 3.dp, outline, RoundedCornerShape(3.dp))
                        .clickable(enabled = enabled && matches, role = Role.Button) { onSlot(number) }
                        .pointerInput(enabled, matches, item?.count, live?.generation, live?.held?.count) {
                            val canLift = drag != null && enabled && matches && (item?.count ?: 0) > 0 && (live?.held?.count ?: 0) == 0
                            if (!canLift || drag == null) return@pointerInput
                            detectDragGestures(
                                onDragStart = { start ->
                                    drag.from = number
                                    drag.icon = dragIcon
                                    drag.finger = origin + start
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag.finger = (drag.finger ?: origin) + amount
                                },
                                onDragEnd = {
                                    val target = drag.finger?.let(drag::slotAt)
                                    if (target != null && target != number) drag.onMove(number, target)
                                    drag.from = null; drag.finger = null; drag.icon = null
                                },
                                onDragCancel = { drag.from = null; drag.finger = null; drag.icon = null }
                            )
                        }
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.Center
                ) {
                    // The game's own icon when the mod sent the item id; the name otherwise.
                    val shown = item?.takeIf { it.count > 0 }
                    val icon = shown?.let { rememberItemIcon(it.id) }
                    if (icon != null && shown != null) {
                        Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize(.74f),
                            filterQuality = FilterQuality.None)
                    } else if (shown != null || live == null) {
                        Text(name, Modifier.padding(horizontal = 3.dp), color = palette.textPrimary, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    if (shown != null && shown.count > 1) Text(shown.count.toString(),
                        Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 1.dp),
                        color = Color.White, fontFamily = PixelFont, fontSize = if (compact) 14.sp else 17.sp,
                        style = TextStyle(shadow = Shadow(Color.Black, Offset(3f, 3f))))
                }
            }
        }
    }
}
