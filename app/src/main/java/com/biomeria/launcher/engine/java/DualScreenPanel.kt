package com.biomeria.launcher.engine.java

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.blur
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
import com.biomeria.launcher.ui.components.BlockButton
import com.biomeria.launcher.ui.components.BlockButtons
import com.biomeria.launcher.ui.components.LauncherButton
import com.biomeria.launcher.ui.theme.PixelFont
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
import com.biomeria.launcher.ui.launch.GameHudControls
import com.biomeria.launcher.ui.launch.PanelPreferenceControls
import com.biomeria.launcher.ui.theme.Biomeria
import com.biomeria.launcher.ui.theme.BiomeriaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

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
        BiomeriaTheme { DualScreenPanel(activity, title, bridge, hud, fps, allowGameInput, allowMenus, onHud, onHotbar) }
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
    // The inventory is home; crafting, map and options open on top of it with a way back.
    var page by remember { mutableStateOf(PanelPage.INVENTORY) }
    var returnPage by remember { mutableStateOf<PanelPage?>(null) }
    var previousMenuPage by remember { mutableStateOf<PanelPage?>(null) }
    var inventory by remember(bridge) { mutableStateOf<PanelInventory?>(null) }
    var pixels by remember(bridge) { mutableStateOf<IntArray?>(null) }
    // Ajustes opens on its menu of tiles; a tile opens its own page.
    var optionGroup by remember { mutableStateOf<OptionGroup?>(null) }
    var optionFocus by remember { mutableIntStateOf(0) }
    var shaderPicker by remember { mutableStateOf(false) }
    val gameOptions = rememberLiveGameOptions(activity.gameDirectory, bridge)
    val shaders = rememberShaderController(activity.gameDirectory, bridge)
    val scope = rememberCoroutineScope()
    fun nudgeFov(by: Int) {
        val options = gameOptions ?: return
        options.change("fov", fovRaw(fovDegrees(options.values["fov"]) + by))
    }
    var tipIndex by remember { mutableIntStateOf(0) }

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
    val menuPage = live?.layout?.menuPage
    val menuSession = live?.takeIf { hud.routes(it.layout) }?.generation
    var customizing by remember { mutableStateOf(false) }
    var characterPadInput by remember { mutableStateOf<((PanelPadAction) -> Unit)?>(null) }
    var craftPadInput by remember { mutableStateOf<((PanelPadAction) -> Unit)?>(null) }
    LaunchedEffect(menuSession, menuPage) {
        if (menuSession != null && menuPage != null) {
            if (previousMenuPage == null) returnPage = page
            previousMenuPage = menuPage
            page = menuPage
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
    val showingMap = bridge != null && page == PanelPage.MAP && hud.panelMap == PanelMapSource.BIOMERIA
    var mapPadInput by remember { mutableStateOf<TerrainPadInput?>(null) }
    val searchable = live != null && interactive &&
        (page == PanelPage.INVENTORY && live.layout == PanelLayout.PERSONAL || page == PanelPage.STORAGE && live.layout.chestRows > 0)
    var editingSearch by remember(live?.generation, page) { mutableStateOf(false) }
    var slotQuery by remember(live?.generation, page) { mutableStateOf("") }
    var searchKey by remember(live?.generation, page) { mutableStateOf(PanelPadSlot(0)) }
    val craftingByRecipes = page == PanelPage.CRAFT && craftPadInput != null
    val unfilteredRows = remember(bridge, live?.layout, page, customizing, craftingByRecipes) {
        when {
            customizing || craftingByRecipes || (bridge != null && live == null) -> emptyList()
            else -> panelPadRows(live?.layout, page)
        }
    }
    val matches = remember(unfilteredRows, live?.slots, slotQuery, searchable) {
        if (searchable && slotQuery.isNotBlank()) filterPanelSlotRows(unfilteredRows, live?.slots.orEmpty(), slotQuery) else null
    }
    val padRows = matches ?: unfilteredRows
    val matchingSlots = remember(matches) { matches?.flatten()?.map(PanelPadSlot::number)?.toSet() }
    var padSlot by remember(live?.generation, page) { mutableStateOf<PanelPadSlot?>(null) }
    val currentPadSlot = padSlot?.takeIf { slot -> padRows.any { slot in it } } ?: padRows.firstOrNull()?.firstOrNull()
    val padFocus = currentPadSlot.takeIf { controllerActive }
    // The item whose actions show in the card: the controller's slot, or the last one touched.
    var touched by remember(live?.generation) { mutableStateOf<Int?>(null) }
    val chosen = if (controllerActive) padFocus?.number else touched

    LaunchedEffect(controllerActive, menuSession, menuPage) {
        if (controllerActive && menuSession != null && menuPage != null) page = menuPage
    }

    val tabs = listOf(PanelPage.INVENTORY, PanelPage.CRAFT, PanelPage.MAP, PanelPage.OPTIONS)
    fun openPage(next: PanelPage) {
        page = next
        tipIndex = 0
    }
    /** "Volver": closes a routed table or container, otherwise goes back to the inventory. */
    fun goBack(fromPad: Boolean) {
        when {
            routed != null && live != null && page == menuPage -> {
                if (bridge?.send(live, PanelCommand.CLOSE_MENU) == true && fromPad) activity.releaseLowerGamepad()
            }
            page != PanelPage.INVENTORY -> openPage(PanelPage.INVENTORY)
            fromPad -> activity.releaseLowerGamepad()
        }
    }
    fun sendCommand(command: PanelCommand, slot: Int) {
        if (live != null && interactive) bridge?.send(live, command, slot)
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
            shaderPicker -> if (action == PanelPadAction.BACK) shaderPicker = false
            action == PanelPadAction.BACK && page == PanelPage.OPTIONS && optionGroup != null -> optionGroup = null
            showingMap && page == actionPage && mapPadInput?.managingWaypoints?.invoke() == true ->
                mapPadInput?.action?.invoke(action)
            customizing -> characterPadInput?.invoke(action)
            editingSearch && searchable && page == actionPage -> when {
                action == PanelPadAction.BACK || action == PanelPadAction.SEARCH -> editingSearch = false
                action == PanelPadAction.PICK -> applySearchKey(searchKey.number)
                action.directional -> searchKey = movePanelPadSlot(inventorySearchRows, searchKey, action) ?: searchKey
                else -> Unit
            }
            action == PanelPadAction.BACK && searchable && slotQuery.isNotBlank() -> slotQuery = ""
            action == PanelPadAction.BACK -> goBack(fromPad = true)
            page != actionPage -> Unit
            action == PanelPadAction.NEXT_TAB || action == PanelPadAction.PREVIOUS_TAB -> {
                if (menuPage == null && bridge != null) {
                    val index = tabs.indexOf(page)
                    val offset = if (action == PanelPadAction.NEXT_TAB) 1 else -1
                    openPage(if (index == -1) PanelPage.INVENTORY else tabs[(index + offset + tabs.size) % tabs.size])
                }
            }
            action == PanelPadAction.MARK && page == PanelPage.INVENTORY && live?.layout == PanelLayout.PERSONAL &&
                bridge != null -> customizing = true
            action == PanelPadAction.SEARCH && searchable -> editingSearch = true
            page == PanelPage.OPTIONS && optionGroup == null -> when {
                action.directional -> optionFocus = moveHubFocus(optionFocus, action)
                action == PanelPadAction.TRANSFER -> nudgeFov(-5)
                action == PanelPadAction.SPLIT -> nudgeFov(5)
                action == PanelPadAction.PICK -> when (optionFocus) {
                    0 -> Unit
                    1 -> shaderPicker = true
                    else -> OptionGroup.entries.getOrNull(optionFocus - 2)?.let { optionGroup = it }
                }
                else -> Unit
            }
            page == PanelPage.OPTIONS -> Unit
            showingMap -> mapPadInput?.action?.invoke(action)
            craftingByRecipes -> craftPadInput?.invoke(action)
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
        activity.openLowerInventory = { openPage(PanelPage.INVENTORY) }
        onDispose { activity.openLowerInventory = null }
    }
    DisposableEffect(activity) {
        val detach = activity.attachLowerPanelInput({ panelAction(it) }, { x, y -> panelPan(x, y) })
        onDispose(detach)
    }

    /** A touch on a slot: on the inventory it first chooses the item; with something in hand it drops it there. */
    val tapSlot: (Int) -> Unit = { slot ->
        if (controllerActive) padSlot = PanelPadSlot(slot)
        val now = live
        val item = now?.slots?.getOrNull(slot)
        when {
            now == null || bridge == null -> Unit
            matchingSlots != null && slot !in matchingSlots -> Unit
            !interactive -> touched = slot
            now.held.count > 0 -> { bridge.send(now, PanelCommand.PICK, slot); touched = slot }
            page != PanelPage.INVENTORY -> bridge.send(now, PanelCommand.PICK, slot)
            touched == slot && (item?.count ?: 0) > 0 -> bridge.send(now, PanelCommand.PICK, slot)
            else -> touched = slot.takeIf { (item?.count ?: 0) > 0 }
        }
    }

    val hints: List<TipPart> = when {
        !controllerActive -> emptyList()
        customizing -> listOf(TipPart("✛", true), TipPart("Recorrer"), TipPart("A", true), TipPart("Elegir"), TipPart("B", true), TipPart("Listo"))
        editingSearch && searchable -> listOf(TipPart("✛", true), TipPart("Teclado"), TipPart("A", true), TipPart("Elegir"),
            TipPart("B", true), TipPart("Aplicar"))
        showingMap -> listOf(TipPart("R", true), TipPart("Mover"), TipPart("X", true), TipPart("Acercar"), TipPart("Y", true),
            TipPart("Alejar"), TipPart("A", true), TipPart("Centrar"), TipPart("R3", true), TipPart("Marcar"), TipPart("B", true), TipPart("Volver"))
        craftingByRecipes -> listOf(TipPart("✛", true), TipPart("Recetas"), TipPart("A", true), TipPart("Elegir o craftear 1"),
            TipPart("Y", true), TipPart("Craftear todo"), TipPart("X", true), TipPart("Categoría"), TipPart("B", true), TipPart("Volver"))
        shaderPicker -> listOf(TipPart("B", true), TipPart("Cerrar shaders"))
        page == PanelPage.OPTIONS && optionGroup == null -> listOf(TipPart("✛", true), TipPart("Elegir"), TipPart("A", true), TipPart("Abrir"),
            TipPart("X/Y", true), TipPart("Visión"), TipPart("Select", true), TipPart("Ajustes completos"))
        page == PanelPage.OPTIONS -> listOf(TipPart("LB/RB", true), TipPart("Secciones"), TipPart("B", true), TipPart("Volver"))
        padFocus != null -> listOfNotNull(TipPart("A", true), TipPart("Tomar o dejar"), TipPart("X", true), TipPart("Mover rápido a la otra parte"),
            TipPart("Y", true), TipPart("La mitad"), TipPart("L2", true), TipPart("Tirar 1"),
            TipPart("Start", true).takeIf { searchable }, TipPart("Buscar").takeIf { searchable },
            TipPart("B", true), TipPart(if (page == PanelPage.INVENTORY) "Devolver mando" else "Volver"))
        else -> listOf(TipPart("LB/RB", true), TipPart("Secciones"), TipPart("B", true), TipPart("Devolver mando"))
    }
    val pageTips = panelTips(page)

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
    val look = remember(hud.panelStyle, hud.highContrast) { panelLook(hud.panelStyle, hud.highContrast) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scaled = remember(density, hud.textSize, hud.interfaceSize) {
        androidx.compose.ui.unit.Density(density.density * hud.interfaceSize.scale, density.fontScale * hud.textSize.scale)
    }
    CompositionLocalProvider(LocalSlotDrag provides dragState, LocalPanelLook provides look,
        androidx.compose.ui.platform.LocalDensity provides scaled) {
    // Select on the quick settings opens the full options above; this screen turns into their guide.
    SideEffect { activity.quickSettingsShowing = page == PanelPage.OPTIONS }
    val guideOpen = activity.optionGuide.open
    LaunchedEffect(guideOpen) {
        if (!guideOpen) {
            gameOptions?.reload()
            shaders.load()
        }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInRoot() }) {
    Column(Modifier.fillMaxSize().background(look.background).safeDrawingPadding()
        .then(if (customizing) Modifier.blur(14.dp) else Modifier)) {
        val shownTabs = tabs + listOfNotNull(PanelPage.STORAGE.takeIf { menuPage == PanelPage.STORAGE })
        PanelTabBar(
            tabs = shownTabs,
            page = page,
            storageTitle = live?.menuTitle?.takeIf { it.isNotBlank() },
            closeLabel = if (routed != null && page == menuPage) "Cerrar" else null,
            onClose = { goBack(fromPad = false) },
            onTab = { openPage(it) }
        )

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            val fill = Modifier.fillMaxSize()
            when {
                page == PanelPage.OPTIONS -> OptionsPage(hud, onHud, optionGroup, { optionGroup = it },
                    focus = optionFocus.takeIf { controllerActive && optionGroup == null },
                    canTurnOff = (live?.held?.count ?: 0) == 0,
                    fov = gameOptions?.let { fovDegrees(it.values["fov"]) },
                    onFov = { degrees -> gameOptions?.change("fov", fovRaw(degrees)) },
                    status = shaders.status ?: gameOptions?.status,
                    shaderCard = { modifier -> ShaderCard(shaders, { shaderPicker = true }, modifier, pictureHeight = 40.dp) },
                    onFullOptions = { activity.openFullOptions() },
                    modifier = fill)
                bridge == null -> Column(fill, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("El inventario, el crafteo y el mapa necesitan Minecraft Java 1.21.4 con Fabric.",
                        color = Biomeria.colors.textPrimary, style = PanelText.body)
                    Text("Barra rápida", color = Biomeria.colors.textMuted, fontWeight = FontWeight.ExtraBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (slot in 0..8) McSlot(slot, null, 52.dp, allowGameInput, onHotbar,
                            focused = padFocus?.number == slot, placeholder = "Casilla ${slot + 1}")
                    }
                }
                live == null -> Text("Entra a un mundo para conectar el panel.", color = Biomeria.colors.textPrimary,
                    style = PanelText.body)
                showingMap -> TerrainMapPane(bridge, fill, controllerActive = controllerActive, onPadInput = { mapPadInput = it })
                page == PanelPage.MAP -> Column(fill, verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    val image = remember(pixels) {
                        pixels?.let { Bitmap.createBitmap(it, DUAL_MAP_SIZE, DUAL_MAP_SIZE, Bitmap.Config.ARGB_8888).asImageBitmap() }
                    }
                    if (image == null) Text("Lleva un mapa explorado en una mano para verlo aquí.", color = Biomeria.colors.textPrimary)
                    else Image(image, contentDescription = "Mapa equipado", modifier = Modifier.weight(1f).aspectRatio(1f),
                        contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                }
                menuPage != null && page == PanelPage.INVENTORY && live.layout != PanelLayout.PERSONAL ->
                    Column(fill, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(live.menuTitle.ifBlank { "Hay un menú abierto" }, color = Biomeria.colors.textPrimary,
                            style = PanelText.section)
                        if (menuSession != null) LauncherButton("Mostrar aquí", { openPage(menuPage) }, primary = true)
                        else Text("Úsalo en la pantalla del juego.", color = Biomeria.colors.textMuted)
                    }
                page == PanelPage.CRAFT && menuPage != null && menuPage != PanelPage.CRAFT -> Text(
                    "Cierra el ${live.menuTitle.ifBlank { "menú" }.lowercase()} para craftear.", color = Biomeria.colors.textPrimary)
                page == PanelPage.CRAFT -> CraftPage(bridge, live, interactive, controllerActive, padFocus, tapSlot,
                    onPadInput = { craftPadInput = it }, modifier = fill)
                page == PanelPage.STORAGE -> StoragePage(live, interactive, padFocus, matchingSlots, tapSlot, fill)
                else -> InventoryPage(
                    live, bridge, interactive, chosen, padFocus, matchingSlots, slotQuery, editingSearch,
                    onTap = tapSlot,
                    onCommand = { command, slot -> sendCommand(command, slot) },
                    onOpenCraft = { openPage(PanelPage.CRAFT) },
                    onCustomize = { customizing = true },
                    onSearch = { editingSearch = !editingSearch },
                    onClearSearch = { slotQuery = ""; editingSearch = false },
                    keyboard = { modifier ->
                        InventorySearchKeyboard(slotQuery, searchKey.takeIf { controllerActive }, ::applySearchKey,
                            { slotQuery = "" }, modifier)
                    },
                    modifier = fill
                )
            }
            if (!interactive && live != null && page != PanelPage.OPTIONS && page != PanelPage.MAP) {
                Text("Vuelve a la vista de juego para mover objetos.", Modifier.align(Alignment.BottomCenter)
                    .background(Color(0xE60B0E11), RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Biomeria.colors.textPrimary, fontWeight = FontWeight.Bold, style = PanelText.body)
            }
        }

        // The strip at the bottom: what the buttons do, the indicators and who has the controller.
        Row(Modifier.fillMaxWidth().background(Kit.ink)
            .drawBehind { drawRect(Kit.line, Offset.Zero, Size(size.width, 3.dp.toPx())) }
            .padding(top = 3.dp, end = 10.dp).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tipModifier = Modifier.weight(1f)
            when {
                hints.isNotEmpty() -> PanelTipStrip(hints, "Mando", null, tipModifier)
                hud.panelTips && pageTips.isNotEmpty() -> PanelTipStrip(pageTips[tipIndex % pageTips.size], "Consejo",
                    if (pageTips.size > 1) ({ tipIndex++ }) else null, tipModifier)
                else -> Spacer(tipModifier)
            }
            if (hud.widgetsBelow) GameWidgets(hud, fps, bridge)
            if (hud.showPadChip) ControllerChip(controllerActive) { activity.releaseLowerGamepad() }
        }
    }
    // The character in its own window, over the blurred panel.
    if (customizing && bridge != null) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null) { customizing = false }
            .safeDrawingPadding().padding(14.dp), contentAlignment = Alignment.Center) {
            CharacterEditor(bridge, onUpload = { activity.pickSkinImage() }, onDone = { customizing = false },
                modifier = Modifier.fillMaxSize().background(Biomeria.colors.surfaceCard, RoundedCornerShape(8.dp))
                    .border(3.dp, Biomeria.colors.borderSubtle, RoundedCornerShape(8.dp))
                    .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null) {}
                    .padding(14.dp),
                controllerActive = controllerActive, onPadInput = { characterPadInput = it })
        }
    }
    if (guideOpen) {
        OptionGuidePage(activity.optionGuide, { activity.closeFullOptions() },
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {}.safeDrawingPadding())
    }
    if (shaderPicker) ShaderPicker(shaders, onClose = { shaderPicker = false }) { pack -> scope.launch { shaders.choose(pack) } }
    // The stack follows the finger while it is being dragged.
    val finger = dragState.finger
    val icon = dragState.icon
    if (finger != null && dragState.from != null) {
        val size = 64.dp
        val half = with(androidx.compose.ui.platform.LocalDensity.current) { size.toPx() / 2 }
        Box(Modifier.offset { IntOffset((finger.x - rootOrigin.x - half).roundToInt(), (finger.y - rootOrigin.y - half - 48.dp.toPx()).roundToInt()) }
            .size(size).background(Color(0xCC1A2026), RoundedCornerShape(4.dp))
            .border(3.dp, Biomeria.colors.gold, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, null, Modifier.fillMaxSize(.8f), filterQuality = FilterQuality.None)
        }
    }
    }
    }
}

/** Hub focus on the Ajustes page: row 0 holds the field of view (0) and shaders (1), then two rows of three tiles. */
private fun moveHubFocus(focus: Int, action: PanelPadAction): Int {
    val rows = listOf(listOf(0, 1), listOf(2, 3, 4), listOf(5, 6, 7))
    val row = rows.indexOfFirst { focus in it }.coerceAtLeast(0)
    val column = rows[row].indexOf(focus).coerceAtLeast(0)
    fun at(r: Int, c: Int) = rows[r][c.coerceIn(0, rows[r].lastIndex)]
    return when (action) {
        PanelPadAction.LEFT -> at(row, column - 1)
        PanelPadAction.RIGHT -> at(row, column + 1)
        PanelPadAction.UP -> if (row == 0) focus else at(row - 1, if (row == 1) (column * 2 + 1) / 3 else column)
        PanelPadAction.DOWN -> if (row == 2) focus else at(row + 1, if (row == 0) column * 2 else column)
        else -> focus
    }
}

private data class TabFace(val label: String, val glyph: Glyph, val color: Color)

private fun tabFace(page: PanelPage, storageTitle: String?) = when (page) {
    PanelPage.INVENTORY -> TabFace("Mochila", Glyph.BAG, Kit.grass)
    PanelPage.CRAFT -> TabFace("Crafteo", Glyph.TABLE, Kit.gold)
    PanelPage.MAP -> TabFace("Mapa", Glyph.MAP, Kit.sky)
    PanelPage.OPTIONS -> TabFace("Rápidos", Glyph.BOLT, Kit.lilac)
    PanelPage.STORAGE -> TabFace(storageTitle ?: "Cofre", Glyph.CHEST, Kit.wood)
}

/**
 * The sections as a hotbar: blocks of the same width, each with its glyph and colour; the chosen one
 * rises and lights up. LB and RB sit fixed at both ends.
 */
@Composable
private fun PanelTabBar(
    tabs: List<PanelPage>,
    page: PanelPage,
    storageTitle: String?,
    closeLabel: String?,
    onClose: () -> Unit,
    onTab: (PanelPage) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Kit.ink)
            .drawBehind { drawRect(Kit.line, Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx())) }
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 3.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (closeLabel != null) {
            BlockButton(onClose, Modifier.padding(bottom = 6.dp).heightIn(min = 42.dp), BlockButtons.danger,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                Text("‹ $closeLabel", fontWeight = FontWeight.ExtraBold, style = PanelText.body, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                PadKey("B")
            }
        }
        if (tabs.size > 1) ShoulderKey("LB")
        tabs.forEach { tab ->
            val face = tabFace(tab, storageTitle)
            val chosen = tab == page
            Row(
                Modifier.weight(1f).height(if (chosen) 52.dp else 46.dp)
                    .background(if (chosen) face.color else Kit.card, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .drawBehind {
                        drawRect(if (chosen) Color(0x66FFFFFF) else Color(0x14FFFFFF), size = Size(size.width, 3.dp.toPx()))
                    }
                    .clickable(role = Role.Tab, onClickLabel = face.label) { onTab(tab) }
                    .semantics { contentDescription = face.label }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)
            ) {
                PixelGlyph(face.glyph, if (chosen) Kit.ink else face.color, Modifier.size(if (chosen) 26.dp else 22.dp))
                Text(face.label, color = if (chosen) Kit.ink else Kit.muted, fontFamily = PixelFont, fontSize = TypeScale.section,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (tabs.size > 1) ShoulderKey("RB")
    }
}

@Composable
private fun ShoulderKey(key: String) {
    Box(Modifier.padding(bottom = 8.dp).size(42.dp, 34.dp).background(Kit.raised, RoundedCornerShape(7.dp))
        .drawBehind { drawRect(Color(0x55000000), Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx())) },
        contentAlignment = Alignment.Center) {
        Text(key, color = Kit.muted, fontWeight = FontWeight.ExtraBold, style = PanelText.body)
    }
}

/**
 * Small chip showing which screen the controller drives. Tapping it while the panel has the
 * controller hands it back to the game; holding Select takes it here.
 */
@Composable
private fun ControllerChip(active: Boolean, onRelease: () -> Unit) {
    val palette = Biomeria.colors
    Text(if (active) "🎮 Panel" else "🎮 Juego · Select",
        Modifier.background(if (active) palette.gold else palette.surfaceElevated, RoundedCornerShape(4.dp))
            .clickable(enabled = active, onClick = onRelease)
            .semantics { contentDescription = if (active) "El mando controla este panel. Toca para devolverlo al juego"
                else "El mando controla el juego. Mantén Select para usarlo aquí" }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        color = if (active) palette.ink else palette.textPrimary, fontWeight = FontWeight.ExtraBold,
        style = PanelText.detail, maxLines = 1)
}

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
