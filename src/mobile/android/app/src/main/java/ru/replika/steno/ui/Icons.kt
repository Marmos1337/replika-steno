package ru.replika.steno.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Small hand-drawn line icons on a 24 grid (stroke 1.75, round caps), tinted by the caller. */
object Ic {
    private fun icon(name: String, stroke: List<String> = emptyList(), fill: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            stroke.forEach {
                addPath(
                    pathData = addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
            fill.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0z"

    val Menu = icon("menu", listOf("M4 7h16", "M4 12h16", "M4 17h10"))
    val Back = icon("back", listOf("M19 12H5", "M11 18l-6-6 6-6"))
    val Plus = icon("plus", listOf("M12 5v14", "M5 12h14"))
    val Mic = icon("mic", listOf("M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z", "M19 11a7 7 0 0 1-14 0", "M12 18v3"))
    val MicOff = icon("micoff", listOf("M3 3l18 18", "M9 9v3a3 3 0 0 0 5.1 2.1", "M15 9.3V6a3 3 0 0 0-5.7-1.3", "M19 11a7 7 0 0 1-1.2 3.9", "M5 11a7 7 0 0 0 10.6 6", "M12 18v3"))
    val Stop = icon("stop", fill = listOf("M9 7h6a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z"))
    val Play = icon("play", fill = listOf("M8 5.6v12.8a1.1 1.1 0 0 0 1.7 0.9l10-6.4a1.1 1.1 0 0 0 0-1.8l-10-6.4A1.1 1.1 0 0 0 8 5.6z"))
    val Pause = icon("pause", fill = listOf("M7.5 5h2a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", "M14.5 5h2a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z"))
    val Rewind = icon("rewind", listOf("M4 12a8 8 0 1 0 2.4-5.7L4 8.6", "M4 4v4.6h4.6"))
    val Forward = icon("forward", listOf("M20 12a8 8 0 1 1-2.4-5.7L20 8.6", "M20 4v4.6h-4.6"))
    val Search = icon("search", listOf(circle(11f, 11f, 6.5f), "M20 20l-4.2-4.2"))
    val Close = icon("close", listOf("M18 6L6 18", "M6 6l12 12"))
    val More = icon("more", fill = listOf(circle(12f, 5.5f, 1.6f), circle(12f, 12f, 1.6f), circle(12f, 18.5f, 1.6f)))
    val Check = icon("check", listOf("M20 6.5L9.5 17 4 11.5"))
    val Up = icon("up", listOf("M18 15l-6-6-6 6"))
    val Down = icon("down", listOf("M6 9l6 6 6-6"))
    val Left = icon("left", listOf("M15 18l-6-6 6-6"))
    val Right = icon("right", listOf("M9 18l6-6-6-6"))
    val Edit = icon("edit", listOf("M13 20h8", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"))
    val Copy = icon("copy", listOf("M10 9h9a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z", "M5 15H4.5A1.5 1.5 0 0 1 3 13.5v-9A1.5 1.5 0 0 1 4.5 3h9A1.5 1.5 0 0 1 15 4.5V5"))
    val Share = icon("share", listOf("M4 12v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7", "M16 6l-4-4-4 4", "M12 2v13"))
    val Download = icon("download", listOf("M20 15v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-4", "M7 10l5 5 5-5", "M12 15V3"))
    val Trash = icon("trash", listOf("M4 7h16", "M18 7l-0.9 12.2a2 2 0 0 1-2 1.8H8.9a2 2 0 0 1-2-1.8L6 7", "M10 11v6", "M14 11v6", "M9 7V4.8a0.8 0.8 0 0 1 0.8-0.8h4.4a0.8 0.8 0 0 1 0.8 0.8V7"))
    val Refresh = icon("refresh", listOf("M20 12a8 8 0 1 1-2.4-5.7L20 8.6", "M20 4v4.6h-4.6"))
    val Sun = icon("sun", listOf(circle(12f, 12f, 4f), "M12 2.5v2", "M12 19.5v2", "M5.3 5.3l1.4 1.4", "M17.3 17.3l1.4 1.4", "M2.5 12h2", "M19.5 12h2", "M5.3 18.7l1.4-1.4", "M17.3 6.7l1.4-1.4"))
    val Moon = icon("moon", listOf("M20.5 13.4A8.5 8.5 0 1 1 10.6 3.5a6.6 6.6 0 0 0 9.9 9.9z"))
    val Auto = icon("auto", listOf(circle(12f, 12f, 8.5f)), listOf("M12 3.5a8.5 8.5 0 0 1 0 17z"))
    val Records = icon("records", listOf("M4 6.5h9", "M4 12h16", "M4 17.5h12"), listOf(circle(18.5f, 6.5f, 1.8f)))
    val Info = icon("info", listOf(circle(12f, 12f, 8.5f), "M12 16.2v-4.7", "M12 8h0.01"))
    val Alert = icon("alert", listOf("M12 9.5v4", "M12 17h0.01", "M10.3 4.1L2.6 17.5A2 2 0 0 0 4.3 20.5h15.4a2 2 0 0 0 1.7-3L13.7 4.1a2 2 0 0 0-3.4 0z"))
    val Question = icon("question", listOf(circle(12f, 12f, 8.5f), "M9.6 9.4a2.5 2.5 0 0 1 4.8 0.9c0 1.7-2.4 2.3-2.4 2.3", "M12 16.4h0.01"))
    val File = icon("file", listOf("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M12 17.5v-6", "M9.2 14.2L12 11.4l2.8 2.8"))
    val Shield = icon("shield", listOf("M12 21s7.5-3.6 7.5-9.4V5.6L12 3 4.5 5.6v6c0 5.8 7.5 9.4 7.5 9.4z", "M9 12l2 2 4-4"))
    val Link = icon("link", listOf("M7 17L17 7", "M8 7h9v9"))
    val Cpu = icon("cpu", listOf("M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M10 10h4v4h-4z", "M9.5 2.5V5", "M14.5 2.5V5", "M9.5 19v2.5", "M14.5 19v2.5", "M2.5 9.5H5", "M2.5 14.5H5", "M19 9.5h2.5", "M19 14.5h2.5"))
    val Settings = icon("settings", listOf("M4 7h10", "M18 7h2", "M4 17h2", "M10 17h10", circle(16f, 7f, 2f), circle(8f, 17f, 2f)))
    val Collapse = icon("collapse", listOf("M4.5 4h15a0.5 0.5 0 0 1 0.5 0.5v15a0.5 0.5 0 0 1-0.5 0.5h-15a0.5 0.5 0 0 1-0.5-0.5v-15A0.5 0.5 0 0 1 4.5 4z", "M9.5 4v16", "M15.5 10l-2 2 2 2"))
    val Expand = icon("expand", listOf("M4.5 4h15a0.5 0.5 0 0 1 0.5 0.5v15a0.5 0.5 0 0 1-0.5 0.5h-15a0.5 0.5 0 0 1-0.5-0.5v-15A0.5 0.5 0 0 1 4.5 4z", "M9.5 4v16", "M13.5 10l2 2-2 2"))
}
