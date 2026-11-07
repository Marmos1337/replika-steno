package ru.replika.steno.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import ru.replika.steno.ui.theme.Motion
import ru.replika.steno.ui.theme.Radius
import ru.replika.steno.ui.theme.Size
import ru.replika.steno.ui.theme.Space
import ru.replika.steno.ui.theme.Theme
import ru.replika.steno.ui.theme.Type

// ---------------------------------------------------------------- brand

/**
 * The product mark (same geometry as the launcher icon): two offset rounded bars are two speech turns,
 * the dot is the playhead.
 */
@Composable
fun Mark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // launcher geometry spans x 28..78, y 40..70 of a 108 grid -> normalise to the canvas
        val s = minOf(size.width / 50f, size.height / 30f)
        val ox = (size.width - 50f * s) / 2f - 28f * s
        val oy = (size.height - 30f * s) / 2f - 40f * s
        val r = CornerRadius(6f * s, 6f * s)
        drawRoundRect(color, Offset(ox + 30f * s, oy + 40f * s), GSize(40f * s, 12f * s), r)
        drawRoundRect(color.copy(alpha = color.alpha * 0.55f), Offset(ox + 42f * s, oy + 58f * s), GSize(36f * s, 12f * s), r)
        drawCircle(color, 5f * s, Offset(ox + 33f * s, oy + 64f * s))
    }
}

@Composable
fun LogoSquare(size: Dp = Size.logo) {
    val c = Theme.colors
    Box(
        Modifier.size(size).clip(Radius.r10).background(c.text),
        contentAlignment = Alignment.Center,
    ) { Mark(c.onText, Modifier.size(size * 0.62f)) }
}

/** Lockup: the mark in a rounded square + the word in an outlined pill. */
@Composable
fun LogoWord() {
    val c = Theme.colors
    Box(
        Modifier.height(Size.logo).border(Size.stroke, c.borderStrong, Radius.pill).padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) { Text("реплика", style = Type.heading, color = c.text, maxLines = 1) }
}

// ---------------------------------------------------------------- buttons

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = Theme.colors
    Row(
        modifier
            .heightIn(min = Size.touch)
            .clip(Radius.pill)
            .background(if (enabled) c.text else c.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (enabled) c.onText else c.muted
        if (icon != null) {
            Icon(icon, null, Modifier.size(Size.iconSmall), tint = fg)
            Spacer(Modifier.width(Space.s))
        }
        Text(text, style = Type.bodyStrong, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = Theme.colors
    Row(
        modifier
            .heightIn(min = Size.touch)
            .clip(Radius.pill)
            .border(Size.stroke, c.borderStrong, Radius.pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (enabled) c.text else c.muted
        if (icon != null) {
            Icon(icon, null, Modifier.size(Size.iconSmall), tint = fg)
            Spacer(Modifier.width(Space.s))
        }
        Text(text, style = Type.bodyStrong, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Outlined pill with a single icon (secondary action next to a primary pill). */
@Composable
fun OutlineIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Box(
        modifier.height(Size.touch).width(Size.touch + Space.xl).clip(Radius.pill)
            .border(Size.stroke, c.borderStrong, Radius.pill)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(Size.icon), tint = c.text) }
}

/** Text-only button for dialogs and inline actions. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Theme.colors.text) {
    Box(
        modifier.heightIn(min = Size.touch).clip(Radius.pill).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.bodyStrong, color = color, maxLines = 1) }
}

@Composable
fun IconBtn(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Theme.colors.text,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    val c = Theme.colors
    Box(
        modifier
            .size(Size.touch)
            .clip(Radius.pill)
            .then(if (filled) Modifier.background(c.text) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, description, Modifier.size(Size.icon),
            tint = when {
                !enabled -> c.muted.copy(alpha = 0.5f)
                filled -> c.onText
                else -> tint
            },
        )
    }
}

// ---------------------------------------------------------------- containers

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(Space.l),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Theme.colors
    Column(
        modifier
            .clip(Radius.r20)
            .background(c.surface)
            .border(Size.stroke, c.border, Radius.r20)
            .then(
                if (onClick != null) {
                    Modifier.combinedClickableCompat(onClick, onLongClick)
                } else Modifier,
            )
            .padding(padding),
        content = content,
    )
}

fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))

/**
 * Fixed action bar at the bottom of a screen. It is a sibling of the scrolling content, never drawn
 * over it: the content ends at its top edge, on an opaque strip of the page color. The list above
 * fades out into that edge ([edgeFade]) instead of being cut by a hairline.
 * [top] is an optional slot above the buttons (a «к новым репликам» pill); it keeps a [Space.m] gap.
 */
@Composable
fun BottomBar(
    side: Dp,
    modifier: Modifier = Modifier,
    top: @Composable ColumnScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val c = Theme.colors
    Column(
        modifier.fillMaxWidth().background(c.bg).navigationBarsPadding()
            .padding(start = side, end = side, top = Space.m, bottom = Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        top()
        content()
    }
}

/**
 * Gradient of [color] (the page background) over the top and/or bottom [height] of a scrolling
 * container, so rows fade out at a fixed edge instead of being sliced flat. [top] and [bottom] are
 * strengths 0..1; pass [fadeWhen] of the list's canScrollBackward / canScrollForward so a list at rest
 * is never dimmed. Apply it outside the scroll modifier, so it stays on the viewport edges.
 */
fun Modifier.edgeFade(color: Color, top: Float, bottom: Float, height: Dp = Size.fade): Modifier =
    if (top <= 0f && bottom <= 0f) this else drawWithContent {
        drawContent()
        val h = height.toPx().coerceAtMost(size.height / 2f)
        if (top > 0f) {
            drawRect(
                Brush.verticalGradient(listOf(color.copy(alpha = color.alpha * top), color.copy(alpha = 0f)), startY = 0f, endY = h),
                size = GSize(size.width, h),
            )
        }
        if (bottom > 0f) {
            drawRect(
                Brush.verticalGradient(listOf(color.copy(alpha = 0f), color.copy(alpha = color.alpha * bottom)), startY = size.height - h, endY = size.height),
                topLeft = Offset(0f, size.height - h),
                size = GSize(size.width, h),
            )
        }
    }

/**
 * Gradient of [color] in the [height] just below this element, drawn over whatever lies there: the
 * edge of a sticky header (tabs) that rows scroll under. Draws nothing at [strength] 0.
 */
fun Modifier.fadeBelow(color: Color, strength: Float, height: Dp = Size.fade): Modifier =
    if (strength <= 0f) this else drawWithContent {
        drawContent()
        val h = height.toPx()
        drawRect(
            Brush.verticalGradient(listOf(color.copy(alpha = color.alpha * strength), color.copy(alpha = 0f)), startY = size.height, endY = size.height + h),
            topLeft = Offset(0f, size.height),
            size = GSize(size.width, h),
        )
    }

/** Animated 0..1 strength for [edgeFade]: the fade appears only while there is content beyond that edge. */
@Composable
fun fadeWhen(active: Boolean): Float {
    val f by animateFloatAsState(if (active) 1f else 0f, Motion.spec(Theme.reducedMotion, Motion.FAST), label = "edgeFade")
    return f
}

/**
 * Raised pill that brings the reader back to the newest or current text. It takes its own row above
 * the bottom bar (a [BottomBar] top slot, or a row of its own with [topGap]), so it never covers text.
 * Secondary style (page fill, outline, shadow): the bar below already holds the one black primary.
 */
@Composable
fun ColumnScope.FloatingPill(visible: Boolean, text: String, topGap: Boolean = false, onClick: () -> Unit) {
    val c = Theme.colors
    val reduced = Theme.reducedMotion
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(Motion.move(reduced)) + fadeIn(Motion.spec(reduced)),
        exit = shrinkVertically(Motion.move(reduced)) + fadeOut(Motion.spec(reduced, Motion.FAST)),
    ) {
        Box(Modifier.padding(top = if (topGap) Space.m else 0.dp, bottom = Space.m)) {
            SecondaryButton(text, onClick, Modifier.shadow(Space.s, Radius.pill).background(c.bg, Radius.pill), icon = Ic.Down)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.heading, color = Theme.colors.text, modifier = modifier)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(Size.stroke).background(Theme.colors.border))
}

/**
 * NEUTRAL: waiting/stopped (grey dot); ACTIVE: in progress (text-colored dot); DONE: check mark;
 * ERROR: red dot; LIVE: a take is recording (the same red as the recording dot everywhere else).
 */
enum class ChipKind { NEUTRAL, ACTIVE, DONE, ERROR, LIVE }

/** Non-interactive status pill; fixed [Size.chip] height so chips of every kind line up. */
@Composable
fun StatusChip(text: String, kind: ChipKind = ChipKind.NEUTRAL, modifier: Modifier = Modifier) {
    val c = Theme.colors
    Row(
        modifier.height(Size.chip).clip(Radius.pill).background(c.raised).padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (kind == ChipKind.DONE) {
            Icon(Ic.Check, null, Modifier.size(Space.l), tint = c.text)
            Spacer(Modifier.width(Space.xs))
        } else {
            Box(
                Modifier.size(Size.dot).clip(Radius.pill).background(
                    when (kind) {
                        ChipKind.ACTIVE -> c.text
                        ChipKind.ERROR, ChipKind.LIVE -> c.danger
                        else -> c.muted
                    },
                ),
            )
            Spacer(Modifier.width(Space.s))
        }
        Text(text, style = Type.labelNum, color = c.text, maxLines = 1, softWrap = false)
    }
}

/** Gives a small control a touch area of at least [Size.touch] in both directions. */
fun Modifier.touchTarget(): Modifier = this.defaultMinSize(minWidth = Size.touch, minHeight = Size.touch)

/**
 * Equal-width segmented control; the selected pill slides between options. [badge] draws an optional
 * small mark after an option's label (a spinner while that tab's content is still being built).
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
    iconsOnly: Boolean = false,
    badge: (@Composable (T) -> Unit)? = null,
) {
    val c = Theme.colors
    val reduced = Theme.reducedMotion
    BoxWithConstraints(
        modifier.height(Size.touch).clip(Radius.pill).background(c.surface).border(Size.stroke, c.border, Radius.pill)
            .padding(Space.xs),
    ) {
        val w = maxWidth / options.size
        val idx = options.indexOf(selected).coerceAtLeast(0)
        val x by animateDpAsState(w * idx, Motion.move(reduced), label = "seg")
        Box(
            Modifier.offset(x = x).width(w).fillMaxHeight().clip(Radius.pill).background(c.selected)
                .border(Size.stroke, c.borderStrong, Radius.pill),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { o ->
                val sel = o == selected
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(Radius.pill)
                        .clickable(role = Role.Tab, onClickLabel = label(o)) { onSelect(o) }
                        .padding(horizontal = Space.s),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Icon(icon(o), if (iconsOnly) label(o) else null, Modifier.size(Size.iconSmall), tint = if (sel) c.text else c.muted)
                    }
                    if (!iconsOnly) {
                        if (icon != null) Spacer(Modifier.width(Space.s))
                        Text(label(o), style = Type.label, color = if (sel) c.text else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    badge?.invoke(o)
                }
            }
        }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.spec(Theme.reducedMotion, Motion.SLOW), label = "progress")
    Box(modifier.fillMaxWidth().height(Size.progress).clip(Radius.pill).background(c.raised)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(Radius.pill).background(c.text))
    }
}

// ---------------------------------------------------------------- inputs

@Composable
fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val c = Theme.colors
    Row(
        modifier.height(Size.touch).clip(Radius.pill).background(c.surface).border(Size.stroke, c.border, Radius.pill)
            .padding(start = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.Search, null, Modifier.size(Size.iconSmall), tint = c.muted)
        Spacer(Modifier.width(Space.s))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = Type.body, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Type.body.copy(color = c.text),
                cursorBrush = SolidColor(c.text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing()
        if (value.isNotEmpty()) IconBtn(Ic.Close, "Очистить", { onChange("") }, tint = c.muted)
        else Spacer(Modifier.width(Space.l))
    }
}

/** Multi-line field for dialogs; [error] space is always reserved so the dialog does not jump. */
@Composable
fun DialogField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = false,
    error: String? = null,
) {
    val c = Theme.colors
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = Size.touch).clip(Radius.r14).background(c.bg)
                .border(Size.stroke, if (error != null) c.danger else c.borderStrong, Radius.r14)
                .padding(horizontal = Space.l, vertical = Space.m),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(placeholder, style = Type.body, color = c.muted)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = singleLine,
                textStyle = Type.body.copy(color = c.text), cursorBrush = SolidColor(c.text),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Space.xs))
        Text(error ?: "", style = Type.label, color = c.danger, minLines = 1, maxLines = 1)
    }
}

// ---------------------------------------------------------------- dialogs and menus

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Theme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(Space.l).widthIn(max = 480.dp).fillMaxWidth().clip(Radius.r26).background(c.surface)
                .border(Size.stroke, c.border, Radius.r26).padding(top = Space.xl, bottom = Space.l),
        ) {
            Text(title, style = Type.heading, color = c.text, modifier = Modifier.padding(horizontal = Space.xl))
            Spacer(Modifier.height(Space.m))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = Space.xl),
                content = content,
            )
            Spacer(Modifier.height(Space.l))
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Space.m),
                horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.End),
                content = buttons,
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    AppDialog(title, onDismiss, buttons = {
        TextAction("Отмена", onDismiss, color = Theme.colors.muted)
        TextAction(confirm, { onConfirm(); onDismiss() }, color = if (danger) Theme.colors.danger else Theme.colors.text)
    }) { Text(text, style = Type.body, color = Theme.colors.muted) }
}

data class MenuEntry(val label: String, val icon: ImageVector, val danger: Boolean = false, val onClick: () -> Unit)

@Composable
fun AppMenu(expanded: Boolean, onDismiss: () -> Unit, entries: List<MenuEntry>) {
    val c = Theme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = Radius.r14,
        containerColor = c.surface,
        border = BorderStroke(Size.stroke, c.border),
        shadowElevation = 8.dp,
        tonalElevation = 0.dp,
    ) {
        entries.forEach { e ->
            val fg = if (e.danger) c.danger else c.text
            DropdownMenuItem(
                text = { Text(e.label, style = Type.body, color = fg) },
                leadingIcon = { Icon(e.icon, null, Modifier.size(Size.iconSmall), tint = fg) },
                onClick = { onDismiss(); e.onClick() },
                modifier = Modifier.heightIn(min = Size.touch),
            )
        }
    }
}

// ---------------------------------------------------------------- states

@Composable
fun EmptyState(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val c = Theme.colors
    Column(
        modifier.padding(horizontal = Space.xl, vertical = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // the empty library keeps the product mark (brand moment) at the same tile size
        if (icon == null) LogoSquare(Size.emptyTile) else EmptyIcon(icon)
        Spacer(Modifier.height(Space.xl))
        Text(title, style = Type.heading, color = c.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(Space.s))
        Text(text, style = Type.body, color = c.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
        Spacer(Modifier.height(Space.xl))
        Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s), content = actions)
    }
}

/** The one tile of empty, error and summary-empty states: 56dp, surface + 1dp border, 24dp icon. */
@Composable
fun EmptyIcon(icon: ImageVector, danger: Boolean = false) {
    val c = Theme.colors
    Box(
        Modifier.size(Size.emptyTile).clip(Radius.r14).background(c.surface).border(Size.stroke, c.border, Radius.r14),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(Size.iconLarge), tint = if (danger) c.danger else c.text) }
}

/** Cross-fade between states while the container keeps its size. */
@Composable
fun <S> Fade(state: S, modifier: Modifier = Modifier, content: @Composable (S) -> Unit) {
    val reduced = Theme.reducedMotion
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = { fadeIn(Motion.spec(reduced)) togetherWith fadeOut(Motion.spec(reduced, Motion.FAST)) },
        contentAlignment = Alignment.TopStart,
        label = "fade",
    ) { content(it) }
}

// ---------------------------------------------------------------- progress

/** Stages the user waits for; formatting and summary run in the background after the transcript is READY. */
val FOREGROUND_STAGES = listOf(ru.replika.steno.core.Stage.DECODE, ru.replika.steno.core.Stage.DIARIZE, ru.replika.steno.core.Stage.RECOGNIZE)

/** Progress of the part before the transcript opens, 0..1. */
fun transcriptProgress(p: ru.replika.steno.core.Progress): Float {
    val total = FOREGROUND_STAGES.sumOf { it.weight.toDouble() }.toFloat()
    return (p.overall / total).coerceIn(0f, 1f)
}

// ---------------------------------------------------------------- formatting

fun fmtTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

private val RU = Locale.forLanguageTag("ru")

fun fmtDate(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val time = SimpleDateFormat("HH:mm", RU).format(Date(ms))
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Сегодня, $time"
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (yesterday) return "Вчера, $time"
    val pattern = if (Calendar.getInstance().get(Calendar.YEAR) == then.get(Calendar.YEAR)) "d MMMM, HH:mm" else "d MMMM yyyy"
    return SimpleDateFormat(pattern, RU).format(Date(ms))
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
    return "$n\u00A0$w" // never break between the number and its word
}

fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "$s с"
        s < 3600 -> "${s / 60} мин ${s % 60} с"
        else -> "${s / 3600} ч ${(s % 3600) / 60} мин"
    }
}
