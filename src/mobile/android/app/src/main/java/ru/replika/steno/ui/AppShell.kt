package ru.replika.steno.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import ru.replika.steno.core.live.LiveState
import ru.replika.steno.core.live.LiveStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.replika.steno.data.ThemeMode
import ru.replika.steno.ui.theme.Motion
import ru.replika.steno.ui.theme.Radius
import ru.replika.steno.ui.theme.Size
import ru.replika.steno.ui.theme.Space
import ru.replika.steno.ui.theme.Theme
import ru.replika.steno.ui.theme.Type

/** Root: drawer (modal on phones, permanent and collapsible on wide screens) + the current screen. */
@Composable
fun AppShell(vm: ReplikaViewModel) {
    val c = Theme.colors
    val screen by vm.screen.collectAsState()
    val theme by vm.theme.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val collapsed by vm.railCollapsed.collectAsState()

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && screen != Screen.Library) { vm.back() }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val wide = maxWidth >= Size.wideBreakpoint
        val go: (Screen) -> Unit = { s ->
            vm.navigate(s)
            scope.launch { drawer.close() }
        }
        val content = @Composable {
            Column(Modifier.fillMaxSize()) {
                val live by vm.live.collectAsState()
                val running = live?.takeIf { screen != Screen.Live && it.status != LiveStatus.DONE }
                // Wide screens keep a full-width strip; on a phone the take shows as a pill inside the top
                // bar, so the screen does not lose another 48dp row to it.
                val strip = running?.takeIf { wide }
                // The shell owns the status bar area, so only the strip's own row slides in and out and
                // the screen below moves with it smoothly; screens never pad for the status bar again.
                val top by animateColorAsState(if (strip != null) c.nav else c.bg, Motion.spec(Theme.reducedMotion), label = "statusArea")
                Box(Modifier.fillMaxWidth().background(top).windowInsetsTopHeight(WindowInsets.statusBars))
                LiveStrip(strip) { vm.navigate(Screen.Live) }
                val indicator = running?.takeIf { !wide }?.let { LiveIndicator(it) { vm.navigate(Screen.Live) } }
                CompositionLocalProvider(LocalLiveIndicator provides indicator) {
                    Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.statusBars)) {
                        ScreenHost(vm, screen, wide, onMenu = { scope.launch { drawer.open() } })
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                val w by animateDpAsState(if (collapsed) Size.rail else Size.drawerWide, Motion.move(Theme.reducedMotion), label = "rail")
                Box(Modifier.width(w).fillMaxHeight().background(c.nav).clipToBounds()) {
                    DrawerPanel(screen, theme, collapsed, modal = false, onToggle = vm::toggleRail, onGo = go, onTheme = vm::setTheme)
                }
                Box(Modifier.width(Size.stroke).fillMaxHeight().background(c.border))
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = drawer.isOpen || screen == Screen.Library || screen == Screen.About,
                scrimColor = c.scrim,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = c.nav,
                        drawerContentColor = c.text,
                        drawerShape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
                        drawerTonalElevation = 0.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        modifier = Modifier.widthIn(max = Size.drawerWide + Space.xl),
                    ) {
                        DrawerPanel(screen, theme, collapsed = false, modal = true, onToggle = { scope.launch { drawer.close() } }, onGo = go, onTheme = vm::setTheme)
                    }
                },
            ) { content() }
        }
        ToastHost(vm, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ScreenHost(vm: ReplikaViewModel, screen: Screen, wide: Boolean, onMenu: () -> Unit) {
    Fade(screen.encode(), Modifier.fillMaxSize()) { key ->
        when (val s = Screen.decode(key)) {
            Screen.Library -> LibraryScreen(vm, wide, onMenu)
            Screen.About -> AboutScreen(vm, wide, onMenu)
            Screen.Live -> LiveScreen(vm)
            is Screen.Processing -> ProcessingScreen(vm, s.id)
            is Screen.Transcript -> TranscriptScreen(vm, s.id)
        }
    }
}

/**
 * «Идёт запись · 03:12» across the top of every other screen while a live take is going: leaving the
 * live screen never stops the recording, and one tap brings it back. Slides in and out with the take.
 */
@Composable
private fun LiveStrip(state: LiveState?, onOpen: () -> Unit) {
    val c = Theme.colors
    var last by remember { mutableStateOf(state) }
    if (state != null) last = state
    AnimatedVisibility(
        visible = state != null,
        enter = expandVertically(Motion.move(Theme.reducedMotion)) + fadeIn(Motion.spec(Theme.reducedMotion)),
        exit = shrinkVertically(Motion.move(Theme.reducedMotion)) + fadeOut(Motion.spec(Theme.reducedMotion, Motion.FAST)),
    ) {
        val s = last ?: return@AnimatedVisibility
        val (text, action) = when (s.status) {
            LiveStatus.STARTING -> "Включаю микрофон…" to "Открыть"
            LiveStatus.RECORDING -> "Идёт запись · ${fmtTime(s.elapsedMs)}" to "Открыть"
            LiveStatus.PAUSED -> "Запись на паузе · ${fmtTime(s.elapsedMs)}" to "Открыть"
            LiveStatus.FINISHING -> "Завершаю запись…" to "Открыть"
            LiveStatus.ERROR -> "Запись остановилась" to "Подробнее"
            LiveStatus.DONE -> "Запись сохранена" to "Открыть"
        }
        Column(Modifier.fillMaxWidth().background(c.nav)) {
            Row(
                Modifier.fillMaxWidth().height(Size.touch)
                    .clickable(role = Role.Button, onClickLabel = "Открыть живую запись", onClick = onOpen)
                    .padding(horizontal = Space.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecDot(s.status, Space.s + Space.xs / 2)
                Spacer(Modifier.width(Space.s))
                Text(text, style = Type.labelNum, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Space.s))
                Text(action, style = Type.label, color = c.text, maxLines = 1)
                Icon(Ic.Right, null, Modifier.size(Size.iconSmall), tint = c.text)
            }
            Box(Modifier.fillMaxWidth().height(Size.stroke).background(c.border))
        }
    }
}

/** A live take in progress, shown by [TopBar] as a trailing pill on phones (see [LiveStrip] for wide screens). */
class LiveIndicator(val state: LiveState, val onOpen: () -> Unit)

val LocalLiveIndicator = compositionLocalOf<LiveIndicator?> { null }

/** Red dot + «03:12» + chevron in the top bar: the take keeps going, one tap brings the live screen back. */
@Composable
private fun LivePill(indicator: LiveIndicator?, endGap: Dp) {
    val c = Theme.colors
    var last by remember { mutableStateOf(indicator?.state) }
    if (indicator != null) last = indicator.state
    AnimatedVisibility(
        visible = indicator != null,
        enter = expandHorizontally(Motion.move(Theme.reducedMotion)) + fadeIn(Motion.spec(Theme.reducedMotion)),
        exit = shrinkHorizontally(Motion.move(Theme.reducedMotion)) + fadeOut(Motion.spec(Theme.reducedMotion, Motion.FAST)),
    ) {
        val s = last ?: return@AnimatedVisibility
        val (text, spoken) = when (s.status) {
            LiveStatus.STARTING -> "Запуск" to "Включаю микрофон"
            LiveStatus.RECORDING -> fmtTime(s.elapsedMs) to "Идёт запись, ${fmtTime(s.elapsedMs)}"
            LiveStatus.PAUSED -> fmtTime(s.elapsedMs) to "Запись на паузе, ${fmtTime(s.elapsedMs)}"
            LiveStatus.FINISHING, LiveStatus.DONE -> "Сохраняю" to "Завершаю запись"
            LiveStatus.ERROR -> "Ошибка" to "Запись остановилась"
        }
        // 48dp touch row around a 32dp visual pill, like the speaker chips
        Box(
            Modifier.padding(end = endGap).height(Size.touch).clip(Radius.pill)
                .clickable(role = Role.Button, onClickLabel = "Открыть живую запись") { indicator?.onOpen?.invoke() ?: Unit }
                .semantics { contentDescription = spoken },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.height(Space.xxl).clip(Radius.pill).background(c.raised).padding(start = Space.m, end = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RecDot(s.status, Size.dot)
                Spacer(Modifier.width(Space.s))
                Text(text, style = Type.labelNum, color = c.text, maxLines = 1)
                Icon(Ic.Right, null, Modifier.size(Size.iconSmall), tint = c.text)
            }
        }
    }
}

@Composable
private fun DrawerPanel(
    screen: Screen,
    theme: ThemeMode,
    collapsed: Boolean,
    modal: Boolean,
    onToggle: () -> Unit,
    onGo: (Screen) -> Unit,
    onTheme: (ThemeMode) -> Unit,
) {
    val c = Theme.colors
    // The logo row is centred on the same line as the top bar of the screen next to it.
    Column(
        Modifier.fillMaxHeight().systemBarsPadding()
            .padding(start = Space.l, end = Space.l, top = (Size.topBar - Size.touch) / 2, bottom = Space.l),
    ) {
        // Logo area: the mark sits in the same 48dp slot in both states. Collapsed, the mark itself expands
        // the rail; on press or focus it cross-fades into the expand icon so the action is readable.
        Row(Modifier.fillMaxWidth().height(Size.touch), verticalAlignment = Alignment.CenterVertically) {
            if (collapsed) {
                val source = remember { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val focused by source.collectIsFocusedAsState()
                val hovered by source.collectIsHoveredAsState()
                Box(
                    Modifier.size(Size.touch).clip(Radius.r10)
                        .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = "Развернуть меню", onClick = onToggle)
                        .semantics { contentDescription = "Развернуть меню" },
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(pressed || focused || hovered, animationSpec = Motion.spec(Theme.reducedMotion, Motion.FAST), label = "railMark") { hint ->
                        Box(Modifier.size(Size.logo), contentAlignment = Alignment.Center) {
                            if (hint) {
                                Box(
                                    Modifier.size(Size.logo).clip(Radius.r10).background(c.selected).border(Size.stroke, c.borderStrong, Radius.r10),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Ic.Expand, null, Modifier.size(Size.icon), tint = c.text) }
                            } else LogoSquare()
                        }
                    }
                }
            } else {
                Box(Modifier.size(Size.touch), contentAlignment = Alignment.Center) { LogoSquare() }
                Spacer(Modifier.width(Space.xs))
                LogoWord()
                Spacer(Modifier.weight(1f))
                IconBtn(Ic.Collapse, if (modal) "Закрыть меню" else "Свернуть меню", onToggle, tint = c.muted)
            }
        }
        Spacer(Modifier.height(Space.xl))
        val onRecords = screen !is Screen.About
        NavItem(Ic.Records, "Записи", onRecords, collapsed) { onGo(Screen.Library) }
        Spacer(Modifier.height(Space.xs))
        NavItem(Ic.Info, "О приложении и модели", !onRecords, collapsed) { onGo(Screen.About) }
        Spacer(Modifier.weight(1f))
        if (collapsed) {
            val next = ThemeMode.entries[(theme.ordinal + 1) % ThemeMode.entries.size]
            IconBtn(themeIcon(theme), "Тема: ${themeLabel(theme)}. Переключить на «${themeLabel(next)}»", { onTheme(next) })
        } else {
            // Two left edges only: containers (items, theme switch) on the panel padding, and icons and
            // small labels on the item-icon column; the logo is centred on that column too.
            Text("Тема", style = Type.label, color = c.muted, modifier = Modifier.padding(start = Space.m))
            Spacer(Modifier.height(Space.s))
            Segmented(
                options = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM),
                selected = theme,
                onSelect = onTheme,
                label = ::themeLabel,
                // labels only: three icons + labels do not fit the 280dp panel without touching
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.m))
            Row(Modifier.padding(start = Space.m), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Shield, null, Modifier.size(Size.icon), tint = c.muted)
                Spacer(Modifier.width(Space.m))
                Text("Всё работает на устройстве", style = Type.label, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

fun themeLabel(m: ThemeMode) = when (m) {
    ThemeMode.LIGHT -> "Светлая"
    ThemeMode.DARK -> "Тёмная"
    ThemeMode.SYSTEM -> "Авто"
}

fun themeIcon(m: ThemeMode): ImageVector = when (m) {
    ThemeMode.LIGHT -> Ic.Sun
    ThemeMode.DARK -> Ic.Moon
    ThemeMode.SYSTEM -> Ic.Auto
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, collapsed: Boolean, onClick: () -> Unit) {
    val c = Theme.colors
    Row(
        Modifier.fillMaxWidth().height(Size.touch).clip(Radius.pill)
            .background(if (selected) c.selected else c.nav)
            .then(if (selected) Modifier.border(Size.stroke, c.borderStrong, Radius.pill) else Modifier)
            .clickable(role = Role.Tab, onClickLabel = label, onClick = onClick)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, if (collapsed) label else null, Modifier.size(Size.icon), tint = if (selected) c.text else c.muted)
        if (!collapsed) {
            Spacer(Modifier.width(Space.m))
            Text(label, style = Type.bodyStrong.copy(fontWeight = if (selected) Type.bodyStrong.fontWeight else Type.body.fontWeight),
                color = if (selected) c.text else c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Top row of a screen: leading control, optional title, trailing actions.
 * [side] is the screen's content gutter: the 48dp buttons sit so their glyphs line up with the content
 * column edges (the same inset as the ⋮ of utterance rows). [divider] shows a hairline under the bar
 * while content is scrolled beneath it. [titleVisible] fades the title in and out (a large title in
 * the content that moves up into the bar once it scrolls away). A live take in progress shows as a
 * pill before the actions ([LocalLiveIndicator]).
 */
@Composable
fun TopBar(
    leading: (@Composable () -> Unit)?,
    side: Dp = Space.l,
    divider: Boolean = false,
    title: String? = null,
    titleVisible: Boolean = true,
    actions: @Composable RowScope.() -> Unit = NoActions,
) {
    val c = Theme.colors
    val line by animateFloatAsState(if (divider) 1f else 0f, Motion.spec(Theme.reducedMotion, Motion.FAST), label = "topDivider")
    val titleAlpha by animateFloatAsState(if (titleVisible) 1f else 0f, Motion.spec(Theme.reducedMotion), label = "topTitle")
    Box(Modifier.fillMaxWidth().height(Size.topBar)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = barInset(side)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) leading() else Spacer(Modifier.width(Space.m))
            if (title != null) {
                Text(title, style = Type.heading, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = Space.xs).alpha(titleAlpha))
            } else Spacer(Modifier.weight(1f))
            // without buttons after it the pill ends on the content gutter, not on the screen edge
            LivePill(LocalLiveIndicator.current, endGap = if (actions === NoActions) side - barInset(side) else Space.xs)
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(Size.stroke).alpha(line).background(c.border))
    }
}

private val NoActions: @Composable RowScope.() -> Unit = {}

/**
 * Inset of a row of 48dp icon buttons so the buttons' glyphs line up with the content gutter: the
 * 22dp icon sits 13dp inside its button and its glyph has ~3dp of air of its own.
 */
fun barInset(side: Dp): Dp = (side - Space.m - Space.xs).coerceAtLeast(0.dp)

/** Side gutter that keeps the content column at most [Size.contentMax] wide and centered. */
fun gutter(maxWidth: Dp): Dp = maxOf(Space.l, (maxWidth - Size.contentMax) / 2)

@Composable
private fun ToastHost(vm: ReplikaViewModel, modifier: Modifier) {
    val c = Theme.colors
    val toast by vm.toast.collectAsState()
    var shown by remember { mutableStateOf<Toast?>(null) }
    LaunchedEffect(toast) {
        val t = toast ?: return@LaunchedEffect
        shown = t
        delay(3_000)
        shown = null
        vm.toastShown(t)
    }
    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn(Motion.spec(Theme.reducedMotion)),
        exit = fadeOut(Motion.spec(Theme.reducedMotion, Motion.FAST)),
        modifier = modifier.navigationBarsPadding().padding(bottom = Size.player + Space.m, start = Space.l, end = Space.l),
    ) {
        var last by remember { mutableStateOf("") }
        shown?.let { last = it.text }
        Box(
            Modifier.widthIn(max = 480.dp).clip(Radius.r14).background(c.text).padding(horizontal = Space.l, vertical = Space.m),
        ) { Text(last, style = Type.body, color = c.onText) }
    }
}
