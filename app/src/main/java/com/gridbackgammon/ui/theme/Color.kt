package com.gridbackgammon.ui.theme

import androidx.compose.ui.graphics.Color
import com.gridbackgammon.game.Player

/**
 * Bauhaus palette, neo-brutalist application.
 *
 * The rule the whole system leans on: exactly one primary per screen region,
 * everything else is Ink on Paper. Colour is a signal, never decoration.
 */
object Grid {
    /** Structure. Every border, every glyph outline, every hard shadow. */
    val Ink = Color(0xFF111111)
    val InkSoft = Color(0xFF4A4A4A)

    /** Ground. Warm off-white, not sterile #FFF. */
    val Paper = Color(0xFFF2F0E9)
    val PaperDeep = Color(0xFFE4E0D4)

    /** Bauhaus primaries. */
    val Red = Color(0xFFD62E1F)
    val Yellow = Color(0xFFF5C518)
    val Blue = Color(0xFF0F4CD1)

    /** Board. The points alternate ink and this. */
    val SquareDark = Color(0xFFD9D3C3)
}

/**
 * Two players, two primaries. The third, yellow, belongs to neither: it is
 * the signal for "you can go here".
 */
val Player.color: Color
    get() = when (this) {
        Player.RED -> Grid.Red
        Player.BLUE -> Grid.Blue
    }

/**
 * Readable foreground for a filled accent. Yellow is far too light to carry
 * paper-coloured text; red, blue and ink are dark enough that it is the only choice.
 */
fun onAccent(accent: Color): Color = if (accent == Grid.Yellow) Grid.Ink else Grid.Paper
