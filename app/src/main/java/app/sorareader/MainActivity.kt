package app.sorareader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import app.sorareader.data.settings.AppPreferences
import app.sorareader.data.settings.ThemeMode
import app.sorareader.ui.navigation.BackupRoute
import app.sorareader.ui.navigation.BrowseRoute
import app.sorareader.ui.navigation.GlobalSearchRoute
import app.sorareader.ui.navigation.HistoryRoute
import app.sorareader.ui.navigation.LibraryRoute
import app.sorareader.ui.navigation.LocalBooksRoute
import app.sorareader.ui.navigation.MoreRoute
import app.sorareader.ui.navigation.NovelRoute
import app.sorareader.ui.navigation.PluginsRoute
import app.sorareader.ui.navigation.ReaderRoute
import app.sorareader.ui.navigation.RepositoriesRoute
import app.sorareader.ui.navigation.DownloadsRoute
import app.sorareader.ui.navigation.CategoriesRoute
import app.sorareader.ui.navigation.AboutRoute
import app.sorareader.ui.navigation.StatsRoute
import app.sorareader.ui.navigation.SettingsRoute
import app.sorareader.ui.navigation.UpdatesRoute
import app.sorareader.ui.screens.backup.BackupScreen
import app.sorareader.ui.screens.browse.BrowseScreen
import app.sorareader.ui.screens.globalsearch.GlobalSearchScreen
import app.sorareader.ui.screens.history.HistoryScreen
import app.sorareader.ui.screens.library.LibraryScreen
import app.sorareader.ui.screens.localbooks.LocalBooksScreen
import app.sorareader.ui.screens.more.MoreScreen
import app.sorareader.ui.screens.novel.NovelDetailScreen
import app.sorareader.ui.screens.reader.ReaderScreen
import app.sorareader.ui.screens.downloads.DownloadsScreen
import app.sorareader.ui.screens.categories.CategoriesScreen
import app.sorareader.ui.screens.plugins.PluginsScreen
import app.sorareader.ui.screens.settings.RepositoriesScreen
import app.sorareader.ui.screens.settings.AboutScreen
import app.sorareader.ui.screens.stats.StatsScreen
import app.sorareader.ui.screens.settings.SettingsScreen
import app.sorareader.ui.screens.updates.UpdatesScreen
import app.sorareader.ui.theme.SoraReaderTheme

class MainActivity : ComponentActivity() {
    // Notifications are optional: the download worker degrades gracefully if denied.
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Nothing to do — downloads run with or without notification permission. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val settings = (application as SoraReaderApp).container.settings
        setContent {
            val prefs by settings.observe().collectAsStateWithLifecycle(initialValue = AppPreferences())
            val darkTheme = when {
                prefs.reader.theme.equals(ThemeMode.LIGHT, ignoreCase = true) -> false
                prefs.reader.theme.equals(ThemeMode.DARK, ignoreCase = true) -> true
                else -> isSystemInDarkTheme()
            }
            SoraReaderTheme(
                darkTheme = darkTheme,
                dynamicColor = prefs.dynamicColor,
                amoled = prefs.amoled,
            ) { SoraReaderRoot() }
        }
    }
}

private data class TopDest(val route: Any, val label: String, val icon: ImageVector)

@Composable
fun SoraReaderRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val topDests = listOf(
        TopDest(LibraryRoute, "Library", Icons.AutoMirrored.Filled.LibraryBooks),
        TopDest(UpdatesRoute, "Updates", Icons.Default.Update),
        TopDest(HistoryRoute, "History", Icons.Default.History),
        TopDest(BrowseRoute, "Browse", Icons.Default.Explore),
        TopDest(MoreRoute, "More", Icons.Default.MoreHoriz),
    )
    val showSuite = topDests.any { td -> destination?.hierarchy?.any { it.hasRoute(td.route::class) } == true }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            if (showSuite) topDests.forEach { td ->
                val selected = destination?.hierarchy?.any { it.hasRoute(td.route::class) } == true
                item(
                    selected = selected,
                    onClick = { navController.navigate(td.route) { popUpTo(LibraryRoute) { saveState = true }; launchSingleTop = true; restoreState = true } },
                    icon = { Icon(td.icon, contentDescription = td.label) },
                    label = { Text(td.label) },
                )
            }
        },
    ) {
        Scaffold { inner ->
            NavHost(navController, startDestination = LibraryRoute, modifier = Modifier.padding(inner)) {
                composable<LibraryRoute> { LibraryScreen(onOpenNovel = { navController.navigate(NovelRoute(it)) }) }
                composable<UpdatesRoute> { UpdatesScreen(onOpenNovel = { navController.navigate(NovelRoute(it)) }) }
                composable<HistoryRoute> { HistoryScreen(onOpenChapter = { novelId, chapterId -> navController.navigate(ReaderRoute(novelId, chapterId)) }) }
                composable<BrowseRoute> {
                    BrowseScreen(
                        onOpenNovel = { navController.navigate(NovelRoute(it)) },
                        onOpenGlobalSearch = { navController.navigate(GlobalSearchRoute) },
                    )
                }
                composable<GlobalSearchRoute> {
                    GlobalSearchScreen(
                        onOpenNovel = { navController.navigate(NovelRoute(it)) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<MoreRoute> {
                    MoreScreen(
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                        onOpenRepositories = { navController.navigate(RepositoriesRoute) },
                        onOpenPlugins = { navController.navigate(PluginsRoute) },
                        onOpenDownloads = { navController.navigate(DownloadsRoute) },
                        onOpenCategories = { navController.navigate(CategoriesRoute) },
                        onOpenAbout = { navController.navigate(AboutRoute) },
                        onOpenStats = { navController.navigate(StatsRoute) },
                        onOpenBackup = { navController.navigate(BackupRoute) },
                        onOpenLocalBooks = { navController.navigate(LocalBooksRoute) },
                    )
                }
                composable<DownloadsRoute> { DownloadsScreen(onBack = { navController.popBackStack() }) }
                composable<CategoriesRoute> { CategoriesScreen(onBack = { navController.popBackStack() }) }
                composable<AboutRoute> { AboutScreen(onBack = { navController.popBackStack() }) }
                composable<StatsRoute> { StatsScreen(onBack = { navController.popBackStack() }) }
                composable<BackupRoute> { BackupScreen(onBack = { navController.popBackStack() }) }
                composable<LocalBooksRoute> { LocalBooksScreen(onBack = { navController.popBackStack() }) }
                composable<SettingsRoute> { SettingsScreen(onBack = { navController.popBackStack() }) }
                composable<RepositoriesRoute> { RepositoriesScreen(onBack = { navController.popBackStack() }) }
                composable<PluginsRoute> { PluginsScreen(onBack = { navController.popBackStack() }) }
                composable<NovelRoute>(
                    deepLinks = listOf(
                        navDeepLink { uriPattern = "sorareader://novel?id={novelId}" },
                        navDeepLink { uriPattern = "sorareader://novel/{novelId}" },
                    ),
                ) { entry ->
                    val route = entry.toRoute<NovelRoute>()
                    NovelDetailScreen(novelId = route.novelId, onBack = { navController.popBackStack() }, onOpenChapter = { navController.navigate(ReaderRoute(route.novelId, it)) })
                }
                composable<ReaderRoute> { entry ->
                    val route = entry.toRoute<ReaderRoute>()
                    ReaderScreen(
                        novelId = route.novelId,
                        chapterId = route.chapterId,
                        onBack = { navController.popBackStack() },
                        onOpenChapter = { novelId, chapterId -> navController.navigate(ReaderRoute(novelId, chapterId)) },
                    )
                }
            }
        }
    }
}
