package com.vknxworks.callctrl.ui.nav

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.vknxworks.callctrl.R
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.calls.CallManager
import com.vknxworks.callctrl.calls.InCallActivity
import com.vknxworks.callctrl.ui.setup.SetupGate
import com.vknxworks.callctrl.ui.components.CtrlBottomBar
import com.vknxworks.callctrl.ui.components.LocalBottomClearance
import com.vknxworks.callctrl.ui.components.TabItem
import com.vknxworks.callctrl.ui.dialer.KeypadScreen
import com.vknxworks.callctrl.ui.people.PeopleScreen
import com.vknxworks.callctrl.ui.recents.RecentsScreen
import com.vknxworks.callctrl.ui.settings.BlockedScreen
import com.vknxworks.callctrl.ui.settings.SettingsScreen
import com.vknxworks.callctrl.ui.settings.YouScreen
import com.vknxworks.callctrl.ui.theme.CtrlColors

@Composable
fun AppRoot() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        MainScaffold()
    }
}

private data class TopDest(val route: Any, @StringRes val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

@Composable
private fun TopDest.item(): TabItem = TabItem(stringResource(label), icon, selectedIcon)

/** Only content tabs: Settings and About open from the profile picture. */
private val topDestinations = listOf(
    TopDest(KeypadRoute, R.string.tab_keypad, Icons.Outlined.Dialpad, Icons.Filled.Dialpad),
    TopDest(RecentsRoute, R.string.tab_recents, Icons.Outlined.History, Icons.Filled.History),
    TopDest(PeopleRoute, R.string.tab_contacts, Icons.Outlined.Contacts, Icons.Filled.Contacts),
)

@Composable
private fun MainScaffold() {
    val context = LocalContext.current
    val c = context.container
    val navController = rememberNavController()
    val navigator = remember(navController) { Navigator(navController) }
    val backStack by navController.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val selectedIndex = topDestinations.indexOfFirst { top -> dest?.hierarchy?.any { it.hasRoute(top.route::class) } == true }

    fun selectTab(i: Int) {
        navController.navigate(topDestinations[i].route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // A missed-call notification opens Recents; a number from another app opens the keypad.
    val showRecents by c.showRecents.collectAsStateWithLifecycle()
    LaunchedEffect(showRecents) {
        if (showRecents) {
            // Wait until the screens exist (opening the app from the notification).
            navController.currentBackStackEntryFlow.first()
            c.showRecents.value = false
            selectTab(1)
        }
    }
    val dial by c.dialNumber.collectAsStateWithLifecycle()
    LaunchedEffect(dial) {
        if (dial != null) {
            navController.currentBackStackEntryFlow.first()
            selectTab(0)
        }
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (maxWidth >= 600.dp) {
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.fillMaxHeight().statusBarsPadding(),
                    ) {
                        Spacer(Modifier.weight(1f))
                        topDestinations.forEachIndexed { i, d ->
                            val top = d.item()
                            val selected = i == selectedIndex
                            NavigationRailItem(
                                selected = selected,
                                onClick = { selectTab(i) },
                                icon = { Icon(if (selected) top.selectedIcon else top.icon, contentDescription = top.label) },
                                label = { Text(top.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = CtrlColors.OnAccent,
                                    selectedTextColor = CtrlColors.Text,
                                    indicatorColor = CtrlColors.Accent,
                                    unselectedIconColor = CtrlColors.Muted,
                                    unselectedTextColor = CtrlColors.Muted,
                                ),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().navigationBarsPadding()) { AppNavHost(navController) }
                }
            } else {
                PhoneLayout(navController, showTabs = selectedIndex >= 0, selectedIndex = selectedIndex, onSelect = ::selectTab)
            }
        }
    }
}

@Composable
private fun PhoneLayout(navController: NavHostController, showTabs: Boolean, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val density = LocalDensity.current
    var overlayHeight by remember { mutableStateOf(0.dp) }
    CompositionLocalProvider(LocalBottomClearance provides if (showTabs) overlayHeight else 0.dp) {
        Box(Modifier.fillMaxSize()) {
            AppNavHost(navController)
            AnimatedVisibility(
                visible = showTabs,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { overlayHeight = with(density) { it.height.toDp() } },
            ) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                    CtrlBottomBar(items = topDestinations.map { it.item() }, selectedIndex = selectedIndex, onSelect = onSelect)
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController) {
    NavHost(navController, startDestination = KeypadRoute) {
        composable<KeypadRoute> { SetupGate { WithCallBanner { KeypadScreen() } } }
        composable<RecentsRoute> { SetupGate { WithCallBanner { RecentsScreen() } } }
        composable<PeopleRoute> { SetupGate { WithCallBanner { PeopleScreen() } } }
        composable<YouRoute> { YouScreen() }
        composable<SettingsRoute> { SettingsScreen() }
        composable<BlockedRoute> { BlockedScreen() }
    }
}

/** While a call is going on, a bar at the top leads back to it. */
@Composable
private fun WithCallBanner(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val calls by CallManager.calls.collectAsStateWithLifecycle()
    val live = calls.firstOrNull { !it.ended }
    Box(Modifier.fillMaxSize()) {
        content()
        if (live != null) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .clip(com.vknxworks.callctrl.ui.theme.CtrlShapes.Button)
                    .background(CtrlColors.Accent)
                    .clickable {
                        runCatching { context.startActivity(Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.PhoneInTalk, null, Modifier.size(20.dp), tint = CtrlColors.OnAccent)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.return_to_call, live.title),
                    style = MaterialTheme.typography.labelLarge,
                    color = CtrlColors.OnAccent,
                    maxLines = 1,
                )
            }
        }
    }
}
