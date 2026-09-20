package com.clipcut.ui.caption

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.clipcut.domain.api.MainPostText
import com.clipcut.ui.PostFlowDestination
import com.clipcut.ui.theme.ClipCutTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The caption screen on its own, reached from the home screen instead of after an export. It is the
 * same screen, moods, hashtags, templates and enhance included; the only differences are that there
 * is no clip to upload and the copy button puts just the caption on the clipboard.
 */
@AndroidEntryPoint
class CaptionGeneratorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClipCutTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(
                        navController = nav,
                        startDestination = PostFlowDestination.Caption.createRoute(CaptionViewModel.STANDALONE_PATH),
                    ) {
                        composable(
                            route = PostFlowDestination.Caption.route,
                            arguments = listOf(navArgument("clipPath") { type = NavType.StringType }),
                        ) {
                            CaptionScreen(onCopyCaption = { caption -> copyToClipboard(caption) })
                        }
                    }
                }
            }
        }
    }

    private fun copyToClipboard(caption: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Caption", MainPostText.captionOnly(caption)))
    }

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, CaptionGeneratorActivity::class.java))
    }
}
