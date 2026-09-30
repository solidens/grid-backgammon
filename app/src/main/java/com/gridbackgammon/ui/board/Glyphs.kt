package com.gridbackgammon.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gridbackgammon.game.Player
import com.gridbackgammon.ui.theme.Grid
import com.gridbackgammon.ui.theme.color

/**
 * Checkers reduced to Bauhaus primitives, as in GRID LUDO: Red is a square,
 * Blue a circle. Colour and silhouette say the same thing twice, so a side is
 * still readable to someone who cannot tell the red from the blue.
 *
 * Extents are fractions of a column, tuned so the two read at equal weight.
 */
private fun extent(player: Player): Float = if (player == Player.RED) 0.78f else 0.88f

/** Outline weight as a fraction of the column, so one checker holds at every size. */
private const val STROKE_RATIO = 0.075f

fun glyphPath(player: Player, box: Rect): Path = Path().apply {
    if (player == Player.RED) addRect(box) else addOval(box)
}

/**
 * Draws one checker centred on [center]. [unit] is the column it is scaled
 * against, in pixels.
 *
 * [shadow] is a hard offset drop in pixels. Only checkers that can be picked up
 * get one: across the whole app, a shadow means "this can be pressed".
 */
fun DrawScope.drawChecker(
    player: Player,
    center: Offset,
    unit: Float,
    fill: Color = player.color,
    outline: Color = Grid.Ink,
    shadow: Float = 0f,
) {
    val size = unit * extent(player)
    val path = glyphPath(player, Rect(center = center, radius = size / 2f))
    val stroke = Stroke(width = unit * STROKE_RATIO)

    if (shadow > 0f) {
        translate(shadow, shadow) {
            drawPath(path, Grid.Ink, style = Fill)
            drawPath(path, Grid.Ink, style = stroke)
        }
    }
    drawPath(path, fill, style = Fill)
    drawPath(path, outline, style = stroke)
}

/** A checker as a small mark in running UI: plates, standings, the title. */
@Composable
fun PlayerGlyph(
    player: Player,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    outline: Color = Grid.Ink,
) {
    Canvas(modifier.size(size)) {
        // The circle spans 0.88 of its column plus half a stroke each side.
        drawChecker(player, this.center, unit = this.size.minDimension / 0.96f, outline = outline)
    }
}

/** A board point, as a mark: the title's third shape. */
@Composable
fun PointGlyph(modifier: Modifier = Modifier, size: Dp = 16.dp, fill: Color = Grid.Yellow) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val inset = w * 0.06f
        val path = Path().apply {
            moveTo(w / 2f, inset)
            lineTo(w - inset, h - inset)
            lineTo(inset, h - inset)
            close()
        }
        drawPath(path, fill)
        drawPath(path, Grid.Ink, style = Stroke(width = w * 0.09f))
    }
}
