package com.clipcut

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.clipcut.ui.ClipCutDestination
import com.clipcut.ui.caption.CaptionScreen
import com.clipcut.ui.cut.CutScreen
import com.clipcut.ui.post.PostScreen
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
                            CutScreen(
                                onExported = { clipPath ->
                                    navController.navigate(ClipCutDestination.Caption.createRoute(clipPath))
                                },
                            )
                        }
                        composable(
                            route = ClipCutDestination.Caption.route,
                            arguments = listOf(navArgument("clipPath") { type = NavType.StringType }),
                        ) { backStackEntry ->
                            val encodedClipPath = backStackEntry.arguments?.getString("clipPath").orEmpty()
                            CaptionScreen(
                                onContinueToPost = { caption, mediaRef ->
                                    navController.navigate(
                                        ClipCutDestination.Post.createRoute(
                                            clipPath = Uri.decode(encodedClipPath),
                                            caption = caption,
                                            mediaRef = mediaRef,
                                        ),
                                    )
                                },
                            )
                        }
                        composable(
                            route = ClipCutDestination.Post.route,
                            arguments = listOf(
                                navArgument("clipPath") { type = NavType.StringType },
                                navArgument("caption") { type = NavType.StringType },
                                navArgument("mediaRefId") { type = NavType.StringType },
                                navArgument("mediaRefUrl") { type = NavType.StringType },
                            ),
                        ) {
                            PostScreen(
                                onDone = {
                                    navController.popBackStack(ClipCutDestination.Cut.route, inclusive = false)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
