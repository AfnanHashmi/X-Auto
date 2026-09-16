package com.clipcut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.clipcut.ui.ClipCutDestination
import com.clipcut.ui.cut.CutScreen
import com.clipcut.ui.theme.ClipCutTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClipCutTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = ClipCutDestination.Cut.route) {
                        composable(ClipCutDestination.Cut.route) {
                            CutScreen()
                        }
                        // Caption and Post destinations are wired in Phase 5/6.
                    }
                }
            }
        }
    }
}
