package com.gridbackgammon.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.gridbackgammon.BuildConfig
import com.gridbackgammon.game.Player
import com.gridbackgammon.game.Setup
import com.gridbackgammon.game.Variant
import com.gridbackgammon.ui.board.PlayerGlyph
import com.gridbackgammon.ui.board.PointGlyph
import com.gridbackgammon.ui.theme.BrutalButton
import com.gridbackgammon.ui.theme.BrutalChoice
import com.gridbackgammon.ui.theme.BrutalField
import com.gridbackgammon.ui.theme.BrutalRule
import com.gridbackgammon.ui.theme.Caption
import com.gridbackgammon.ui.theme.Grid
import com.gridbackgammon.ui.theme.GridTokens
import com.gridbackgammon.ui.theme.Motion

@Composable
fun HomeScreen(
    setup: Setup,
    onVariant: (Variant) -> Unit,
    onVsBot: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onStart: () -> Unit,
    onlineAvailable: Boolean,
    license: String,
    onLicense: (String) -> Unit,
    onOnline: () -> Unit,
    onHost: () -> Unit,
    onJoin: (String) -> Unit,
) {
    val online = setup.online && onlineAvailable
    var code by rememberSaveable { mutableStateOf("") }
    // Start is pinned to the bottom edge; only the choices above it scroll. On a
    // tall phone nothing scrolls at all, on a small one the button never hides.
    Column(
        Modifier
            .fillMaxSize()
            .background(Grid.Paper)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GridTokens.Page)
                .padding(top = GridTokens.GapWide),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "GRID",
                    style = MaterialTheme.typography.displayLarge,
                    color = Grid.Ink,
                )
                Spacer(Modifier.width(GridTokens.GapWide))
                // Red is the word below; these are the other side and the point they race along.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlayerGlyph(Player.BLUE, size = 22.dp)
                    PointGlyph(size = 22.dp)
                }
            }
            Wordmark("BACKGAMMON", Grid.Red)

            Spacer(Modifier.height(GridTokens.Gap))
            BrutalRule()
            Spacer(Modifier.height(GridTokens.Gap))
            Caption(if (online) "Two phones · by room code" else "One phone · no network required")

            Spacer(Modifier.height(GridTokens.GapSection))
            Caption("Game")
            Spacer(Modifier.height(GridTokens.Gap))
            Row(horizontalArrangement = Arrangement.spacedBy(GridTokens.GapWide)) {
                for (variant in Variant.entries) {
                    Choice(variant.title, variant.detail, setup.variant == variant, Modifier.weight(1f)) {
                        onVariant(variant)
                    }
                }
            }
            Spacer(Modifier.height(GridTokens.Gap))
            Crossfade(setup.variant, animationSpec = Motion.fade(), label = "gist") { variant ->
                Text(
                    when (variant) {
                        Variant.SHORT -> "Opposite ways round. A lone checker can be hit and sent to the bar."
                        Variant.LONG -> "The same way round, all from one stack. One checker holds a point; nothing is hit."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Grid.InkSoft,
                    minLines = 2,
                )
            }

            Spacer(Modifier.height(GridTokens.GapWide))
            Caption("Opponent")
            Spacer(Modifier.height(GridTokens.Gap))
            Row(horizontalArrangement = Arrangement.spacedBy(if (onlineAvailable) GridTokens.Gap else GridTokens.GapWide)) {
                Choice("Bot", "You: red", setup.vsBot && !online, Modifier.weight(1f)) { onVsBot(true) }
                Choice("Friend", "Pass it", !setup.vsBot && !online, Modifier.weight(1f)) { onVsBot(false) }
                if (onlineAvailable) Choice("Online", "By code", online, Modifier.weight(1f), onOnline)
            }
            if (online) {
                OnlineChoices(license, onLicense, code, { code = it }, onJoin = { onJoin(code) })
            }

            Spacer(Modifier.height(GridTokens.GapWide))
            Caption("Feel")
            Spacer(Modifier.height(GridTokens.Gap))
            BrutalChoice(
                selected = setup.haptics,
                onSelect = { onHaptics(!setup.haptics) },
                height = 52.dp,
                contentPadding = PaddingValues(horizontal = GridTokens.GapWide),
            ) {
                val ink = if (setup.haptics) Grid.Paper else Grid.Ink
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("HAPTICS", style = MaterialTheme.typography.titleLarge, color = ink)
                    Spacer(Modifier.weight(1f))
                    Text(if (setup.haptics) "ON" else "OFF", style = MaterialTheme.typography.titleLarge, color = ink)
                }
            }
            Spacer(Modifier.height(GridTokens.GapWide))
        }

        BrutalRule()
        BrutalButton(
            label = if (online) "Open room" else "Start",
            onClick = if (online) onHost else onStart,
            enabled = !online || license.isNotBlank(),
            fill = Grid.Yellow,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GridTokens.Page)
                .padding(top = GridTokens.GapWide, bottom = GridTokens.Gap),
        )
    }
}

@Composable
private fun OnlineChoices(
    license: String,
    onLicense: (String) -> Unit,
    code: String,
    onCode: (String) -> Unit,
    onJoin: () -> Unit,
) {
    val uri = LocalUriHandler.current

    Spacer(Modifier.height(GridTokens.GapWide))
    Caption("Join a friend's room")
    Spacer(Modifier.height(GridTokens.Gap))
    Row(horizontalArrangement = Arrangement.spacedBy(GridTokens.GapWide), verticalAlignment = Alignment.Top) {
        BrutalField(
            value = code,
            // Codes are four letters; I and O are never used, so a typed 1 or 0 is not one either.
            onValueChange = { typed -> onCode(typed.uppercase().filter { it in 'A'..'Z' }.take(4)) },
            placeholder = "Code",
            textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
            modifier = Modifier.weight(1f),
        )
        BrutalButton(
            label = "Join",
            onClick = onJoin,
            enabled = code.length == 4,
            fill = Grid.Blue,
            contentColor = Grid.Paper,
            height = 56.dp,
            modifier = Modifier.width(120.dp),
        )
    }
    Caption("Free · you play your friend's game")

    Spacer(Modifier.height(GridTokens.GapWide))
    Caption("Or open your own, in the game above")
    Spacer(Modifier.height(GridTokens.Gap))
    BrutalField(
        value = license,
        onValueChange = onLicense,
        placeholder = "Licence key",
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    if (BuildConfig.GUMROAD_URL.isNotEmpty()) {
        Caption(
            "Get a key →",
            color = Grid.Blue,
            modifier = Modifier
                .clickable { uri.openUri(BuildConfig.GUMROAD_URL) }
                .padding(vertical = GridTokens.Gap),
        )
    } else {
        Caption("Opening a room takes a licence key")
    }
}

@Composable
private fun Choice(
    title: String,
    detail: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val ink = if (selected) Grid.Paper else Grid.Ink
    BrutalChoice(
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        height = 64.dp,
        contentPadding = PaddingValues(horizontal = GridTokens.GapWide),
    ) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = ink)
            Caption(detail, color = if (selected) ink.copy(alpha = 0.7f) else Grid.InkSoft)
        }
    }
}

/**
 * A word set as large as the title style allows and no larger than the column:
 * ten letters of BACKGAMMON do not fit a narrow phone at the display size.
 */
@Composable
private fun Wordmark(word: String, color: Color) {
    val style = MaterialTheme.typography.displayLarge
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val natural = measurer.measure(word, style).size.width
        val room = constraints.maxWidth
        val scale = if (natural > room) room / natural.toFloat() else 1f
        Text(
            word,
            style = style.copy(
                fontSize = style.fontSize * scale,
                lineHeight = style.lineHeight * scale,
                letterSpacing = style.letterSpacing * scale,
            ),
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}
