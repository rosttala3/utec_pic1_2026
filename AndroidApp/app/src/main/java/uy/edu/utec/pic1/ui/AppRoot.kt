package uy.edu.utec.pic1.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import uy.edu.utec.pic1.App
import uy.edu.utec.pic1.R
import uy.edu.utec.pic1.link.LinkState
import uy.edu.utec.pic1.ui.history.HistoryDetailScreen
import uy.edu.utec.pic1.ui.history.HistoryScreen
import uy.edu.utec.pic1.ui.live.LiveScreen
import uy.edu.utec.pic1.ui.manual.ManualScreen
import uy.edu.utec.pic1.ui.settings.BlinkScreen
import uy.edu.utec.pic1.ui.settings.DiagnosticsScreen
import uy.edu.utec.pic1.ui.settings.SettingsScreen
import uy.edu.utec.pic1.ui.theme.Celeste
import uy.edu.utec.pic1.ui.theme.Metal
import uy.edu.utec.pic1.ui.theme.StatusErr
import uy.edu.utec.pic1.ui.theme.StatusOk

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Live("live", "Cámara", Icons.Default.Sensors),
    Manual("manual", "Manual", Icons.Default.Tune),
    History("history", "Historial", Icons.Default.History),
    Settings("settings", "Ajustes", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(app: App) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val live by app.session.live.collectAsStateWithLifecycle()
    val inTab = Tab.entries.any { it.route == route }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (inTab) TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.logo_utecorroe), null, Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("UTECorroe")
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(10.dp).clip(CircleShape).background(when (live.link) {
                        LinkState.Connected -> StatusOk; is LinkState.Error -> StatusErr; LinkState.Connecting -> Celeste; else -> Metal
                    }))
                }
            })
        },
        bottomBar = {
            if (inTab) NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
                        },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Tab.Live.route, modifier = Modifier.padding(pad)) {
            composable(Tab.Live.route) { LiveScreen(app, snackbar) }
            composable(Tab.Manual.route) { ManualScreen(app, snackbar) }
            composable(Tab.History.route) { HistoryScreen(app, onOpen = { nav.navigate("history/$it") }) }
            composable("history/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
                HistoryDetailScreen(app, it.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
            }
            composable(Tab.Settings.route) { SettingsScreen(app, snackbar, onDiagnostics = { nav.navigate("diag") }) }
            composable("diag") { DiagnosticsScreen(app, onBack = { nav.popBackStack() }, onBleTest = { nav.navigate("ble-test") }) }
            composable("ble-test") { BlinkScreen() }
        }
    }
}
