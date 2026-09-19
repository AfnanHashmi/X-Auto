package com.clipcut.ui.deletion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.clipcut.ui.theme.ClipCutTheme
import dagger.hilt.android.AndroidEntryPoint

/** Trigger Deletion: its own Activity so nothing in it can reach the clip-cutting flow. */
@AndroidEntryPoint
class DeletionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClipCutTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = ROUTE_DELETION) {
                        composable(ROUTE_DELETION) {
                            DeletionScreen(onOpenHistory = { nav.navigate(ROUTE_HISTORY) }, onExit = { finish() })
                        }
                        composable(ROUTE_HISTORY) {
                            DeletionHistoryScreen(onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val ROUTE_DELETION = "deletion"
        private const val ROUTE_HISTORY = "history"

        fun start(context: Context) {
            context.startActivity(Intent(context, DeletionActivity::class.java))
        }
    }
}
