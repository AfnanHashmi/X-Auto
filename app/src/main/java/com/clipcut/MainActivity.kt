package com.clipcut

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.clipcut.ui.ClipCutDestination
import com.clipcut.ui.caption.CaptionScreen
import com.clipcut.ui.cut.CutScreen
import com.clipcut.ui.cut.CutViewModel
import com.clipcut.ui.cut.PreviewScreen
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
                    NavHost(navController = navController, startDestination = ClipCutDestination.CutFlow.route) {
                        navigation(
                            startDestination = ClipCutDestination.Cut.route,
                            route = ClipCutDestination.CutFlow.route,
                        ) {
                            composable(ClipCutDestination.Cut.route) { backStackEntry ->
                                val parentEntry = remember(backStackEntry) {
                                    navController.getBackStackEntry(ClipCutDestination.CutFlow.route)
                                }
                                val cutViewModel: CutViewModel = hiltViewModel(parentEntry)
                                CutScreen(
                                    viewModel = cutViewModel,
                                    onPreview = { navController.navigate(ClipCutDestination.Preview.route) },
                                )
                            }
                            composable(ClipCutDestination.Preview.route) { backStackEntry ->
                                val parentEntry = remember(backStackEntry) {
                                    navController.getBackStackEntry(ClipCutDestination.CutFlow.route)
                                }
                                val cutViewModel: CutViewModel = hiltViewModel(parentEntry)
                                PreviewScreen(
                                    viewModel = cutViewModel,
                                    onExported = { clipPath ->
                                        navController.navigate(ClipCutDestination.Caption.createRoute(clipPath))
                                    },
                                    onCancel = {
                                        navController.popBackStack(ClipCutDestination.Cut.route, inclusive = false)
                                    },
                                )
                            }
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
                                navArgument("mediaRefAuthorHandle") { type = NavType.StringType },
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
