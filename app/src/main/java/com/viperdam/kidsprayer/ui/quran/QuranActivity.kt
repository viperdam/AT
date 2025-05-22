package com.viperdam.kidsprayer.ui.quran // Adjust package if needed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue // Import necessary for NavController state
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.viperdam.kidsprayer.R
import com.viperdam.kidsprayer.ui.theme.KidsPrayerTheme // Make sure this theme exists
import com.viperdam.kidsprayer.utils.SystemBarsUtil
import dagger.hilt.android.AndroidEntryPoint
import android.util.Log // Add Log import

private const val TAG = "QuranActivity"

// Define navigation routes
object QuranDestinations {
    const val LIST_ROUTE = "quranList"
    const val DETAIL_ROUTE = "surahDetail/{surahNumber}"
    fun detailRoute(surahNumber: Int) = "surahDetail/$surahNumber"
}

@AndroidEntryPoint
class QuranActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate - START") // Log 1: Start of onCreate
        enableEdgeToEdge() // Handle edge-to-edge for this activity
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate - Before setContent") // Log 2: Before setContent
        setContent {
            Log.d(TAG, "onCreate - Inside setContent") // Log 3: Inside setContent
            KidsPrayerTheme {
                val navController = rememberNavController()
                val currentBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentBackStackEntry?.destination?.route

                Scaffold(
                    topBar = {
                        TopAppBar(
                            // Dynamically set title based on route
                            title = {
                                Text(stringResource(id = if (currentRoute == QuranDestinations.LIST_ROUTE) R.string.quran_title else R.string.surah_detail))
                            },
                            navigationIcon = {
                                // Show back arrow only on detail screen
                                if (currentRoute != QuranDestinations.LIST_ROUTE) {
                                    IconButton(onClick = { navController.popBackStack() }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(id = R.string.back)
                                        )
                                    }
                                } else {
                                     // Optional: Show different icon or nothing on list screen
                                     // Or handle finish() differently if list is the only screen
                                     IconButton(onClick = { finish() }) { // Default back action for the list screen
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                                     }
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    // Setup NavHost
                    NavHost(
                        navController = navController,
                        startDestination = QuranDestinations.LIST_ROUTE,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        // List Screen
                        composable(QuranDestinations.LIST_ROUTE) {
                            QuranScreen(
                                // Pass the navigation lambda
                                onNavigateToSurahDetail = { surahNumber ->
                                    navController.navigate(QuranDestinations.detailRoute(surahNumber))
                                }
                            )
                        }

                        // Detail Screen
                        composable(
                            route = QuranDestinations.DETAIL_ROUTE,
                            arguments = listOf(navArgument("surahNumber") { type = NavType.IntType })
                        ) { backStackEntry ->
                            val surahNumber = backStackEntry.arguments?.getInt("surahNumber")
                            if (surahNumber != null) {
                                SurahDetailScreen(
                                    surahNumber = surahNumber,
                                    onBack = { navController.popBackStack() } // Use NavController to go back
                                )
                            } else {
                                // Handle error: Invalid surah number passed
                                Log.e(TAG, "Invalid surah number received in navigation argument.")
                                // Optionally navigate back or show an error message
                                navController.popBackStack()
                            }
                        }
                    }
                }
            }
        }

        // Optional: Apply system bar styling consistent with MainActivity
        // SystemBarsUtil.setupEdgeToEdge(this) { view, insets ->
            // Adjust padding within QuranScreen if necessary
        // }
    }
} 