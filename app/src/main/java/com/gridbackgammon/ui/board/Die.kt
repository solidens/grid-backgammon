package com.gridbackgammon.ui.board

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gridbackgammon.ui.theme.BrutalSlab
import com.gridbackgammon.ui.theme.Grid
import com.gridbackgammon.ui.theme.GridTokens
import com.gridbackgammon.ui.theme.Motion
import com.gridbackgammon.ui.theme.rememberBreath

/** Pip positions per face, on a 3x3 lattice. */
private val Pips = mapOf(
    1 to listOf(1 to 1),
    2 to listOf(0 to 0, 2 to 2),
    3 to listOf(0 to 0, 1 to 1, 2 to 2),
    4 to listOf(0 to 0, 2 to 0, 0 to 2, 2 to 2),
    5 to listOf(0 to 0, 2 to 0, 1 to 1, 0 to 2, 2 to 2),
    6 to listOf(0 to 0, 2 to 0, 0 to 1, 2 to 1, 0 to 2, 2 to 2),
)

/**
 * A die is a slab like any other, and it says whose turn it is the way the
 * rest of the app says anything: the dice on turn stand on their shadow, the
 * others lie flat.
 *
 * A throw is a press that lasts: the die stays down in its shadow while the
 * faces run, and comes back up on the result. A die that has been played is
 * [spent]: flat, and greyed back into the paper.
 *
 * Square pips. No radii anywhere includes here.
 */
@Composable
fun Die(
    face: Int,
    fill: Color,
    pip: Color,
    raised: Boolean,
    rollable: Boolean,
    rolling: Boolean,
    onRoll: () -> Unit,
    modifier: Modifier = Modifier,
    spent: Boolean = false,
    size: Dp = DIE,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shadow by animateDpAsState(
        targetValue = if (raised && !spent) GridTokens.ShadowSmall else 0.dp,
        animationSpec = Motion.settle(),
        label = "raise",
    )
    val depth by animateDpAsState(
        targetValue = if (rolling || (pressed && rollable)) GridTokens.ShadowSmall else 0.dp,
        animationSpec = Motion.press(),
        label = "press",
    )
    // Waiting for a throw is the one moment a die asks for attention.
    val pulse = rememberBreath(rollable)
    // Cut, a change of colour reads as a blink in the corner of the eye;
    // crossed over, as a hand-off.
    val body by animateColorAsState(if (spent) Grid.PaperDeep else fill, Motion.fade(), label = "fill")
    val dots by animateColorAsState(if (spent) Grid.InkSoft else pip, Motion.fade(), label = "pip")
    val edge by animateColorAsState(if (spent) Grid.InkSoft else Grid.Ink, Motion.fade(), label = "edge")

    Box(modifier.size(size + GridTokens.ShadowSmall)) {
        BrutalSlab(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val s = 1f + 0.07f * pulse.value
                    scaleX = s
                    scaleY = s
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = rollable,
                    onClick = onRoll,
                ),
            fill = body,
            border = edge,
            shadow = shadow,
            pressedDepth = depth.coerceAtMost(shadow),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val side = this.size.minDimension
                val dot = side * 0.17f
                for ((col, row) in Pips[face.coerceIn(1, 6)].orEmpty()) {
                    val cx = side * (0.27f + 0.23f * col)
                    val cy = side * (0.27f + 0.23f * row)
                    drawRect(dots, Offset(cx - dot / 2f, cy - dot / 2f), Size(dot, dot))
                }
            }
        }
    }
}

val DIE = 44.dp
