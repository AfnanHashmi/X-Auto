package com.clipcut.lan

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.clipcut.ui.PostFlowDestination
import com.clipcut.ui.caption.CaptionScreen
import com.clipcut.ui.post.PostScreen
import com.clipcut.ui.theme.ClipCutTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Hosts the caption -> post-to-X flow, launched via a plain Intent from [LanBufferActivity] after
 * a successful export (see its "clipPath" extra). Kept as a separate Activity, not folded into
 * LanBufferActivity's own composable, so the export/mark-in-out flow -- fragile and actively
 * iterated on all session -- can never be affected by anything in the caption/post path, and vice
 * versa. Same isolation principle LanBufferActivity itself was built with.
 */
@AndroidEntryPoint
class CaptionPostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clipPath = intent.getStringExtra(EXTRA_CLIP_PATH) ?: run {
            finish()
            return
        }
        setContent {
            ClipCutTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = PostFlowDestination.Caption.createRoute(clipPath),
                    ) {
                        composable(
                            route = PostFlowDestination.Caption.route,
                            arguments = listOf(navArgument("clipPath") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val encodedClipPath = backStackEntry.arguments?.getString("clipPath").orEmpty()
                            CaptionScreen(
                                onContinueToPost = { caption, mediaRef ->
                                    navController.navigate(
                                        PostFlowDestination.Post.createRoute(
                                            clipPath = Uri.decode(encodedClipPath),
                                            caption = caption,
                                            mediaRef = mediaRef,
                                        ),
                                    )
                                },
                            )
                        }
                        composable(
                            route = PostFlowDestination.Post.route,
                            arguments = listOf(
                                navArgument("clipPath") { type = NavType.StringType },
                                navArgument("caption") { type = NavType.StringType },
                                navArgument("mediaRefId") { type = NavType.StringType },
                                navArgument("mediaRefUrl") { type = NavType.StringType },
                            ),
                        ) {
                            PostScreen(onDone = { finish() })
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_CLIP_PATH = "clipPath"

        fun start(context: Context, clipPath: String) {
            context.startActivity(
                Intent(context, CaptionPostActivity::class.java).putExtra(EXTRA_CLIP_PATH, clipPath),
            )
        }
    }
}
