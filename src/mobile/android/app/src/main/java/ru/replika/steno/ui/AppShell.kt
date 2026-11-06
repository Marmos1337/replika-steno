package ru.replika.steno.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.collectAsState
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
            ScreenHost(vm, screen, wide, onMenu = { scope.launch { drawer.open() } })
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
            is Screen.Processing -> ProcessingScreen(vm, s.id)
            is Screen.Transcript -> TranscriptScreen(vm, s.id)
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
    Column(Modifier.fillMaxHeight().systemBarsPadding().padding(Space.l)) {
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
            Text("Тема", style = Type.label, color = c.muted, modifier = Modifier.padding(start = Space.xs))
            Spacer(Modifier.height(Space.s))
            Segmented(
                options = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM),
                selected = theme,
                onSelect = onTheme,
                label = ::themeLabel,
                icon = ::themeIcon,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.m))
            Row(Modifier.padding(start = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Shield, null, Modifier.size(Size.iconSmall), tint = c.muted)
                Spacer(Modifier.width(Space.s))
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
 * while content is scrolled beneath it.
 */
@Composable
fun TopBar(
    leading: (@Composable () -> Unit)?,
    side: Dp = Space.l,
    divider: Boolean = false,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Theme.colors
    val line by animateFloatAsState(if (divider) 1f else 0f, Motion.spec(Theme.reducedMotion, Motion.FAST), label = "topDivider")
    Box(Modifier.fillMaxWidth().height(Size.topBar)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = barInset(side)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) leading() else Spacer(Modifier.width(Space.m))
            if (title != null) {
                Text(title, style = Type.heading, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = Space.xs))
            } else Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(Size.stroke).alpha(line).background(c.border))
    }
}

/** Inset of a row of 48dp icon buttons so the buttons' glyphs line up with the content gutter. */
fun barInset(side: Dp): Dp = (side - Space.m).coerceAtLeast(Space.xs)

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
