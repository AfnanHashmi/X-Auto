package com.clipcut.ui.bossmeter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.clipcut.ui.theme.ClipCutTheme
import dagger.hilt.android.AndroidEntryPoint

/** Boss Meter: its own Activity, like the other tools, so it cannot reach the clip-cutting flow. */
@AndroidEntryPoint
class BossMeterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClipCutTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BossMeterScreen(onExit = { finish() })
                }
            }
        }
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, BossMeterActivity::class.java))
    }
}
