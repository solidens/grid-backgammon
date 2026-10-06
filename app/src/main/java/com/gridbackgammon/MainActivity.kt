package com.gridbackgammon

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gridbackgammon.game.GameViewModel
import com.gridbackgammon.ui.screens.GameScreen
import com.gridbackgammon.ui.screens.HomeScreen
import com.gridbackgammon.ui.theme.GridBackgammonTheme
import com.gridbackgammon.ui.theme.Motion

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app has one scheme and it is light. Left on auto, a phone in dark
        // mode would draw white status icons onto the paper.
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            GridBackgammonTheme {
                val vm: GameViewModel = viewModel()
                val setup by vm.setup.collectAsStateWithLifecycle()
                var inGame by remember { mutableStateOf(false) }

                Crossfade(inGame, animationSpec = Motion.fade(), label = "screen") { playing ->
                    if (playing) {
                        GameScreen(
                            viewModel = vm,
                            onExit = {
                                vm.leave()
                                inGame = false
                            },
                        )
                    } else {
                        val license by vm.license.collectAsStateWithLifecycle()
                        HomeScreen(
                            setup = setup,
                            onVariant = vm::setVariant,
                            onVsBot = vm::setVsBot,
                            onHaptics = vm::setHaptics,
                            onStart = {
                                vm.newGame(fresh = true)
                                inGame = true
                            },
                            onlineAvailable = vm.onlineAvailable,
                            license = license,
                            onLicense = vm::setLicense,
                            onOnline = vm::setOnline,
                            onHost = {
                                vm.host()
                                inGame = true
                            },
                            onJoin = { code ->
                                vm.join(code)
                                inGame = true
                            },
                        )
                    }
                }
            }
        }
    }
}
