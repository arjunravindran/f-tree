package com.vibethroughcode.ftree.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.vibethroughcode.ftree.BuildConfig
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.R
import com.vibethroughcode.ftree.nearby.NearbyState
import com.vibethroughcode.ftree.transfer.TreeDocument
import com.vibethroughcode.ftree.transfer.sendBranchIntent
import com.vibethroughcode.ftree.ui.common.LocalKinshipLanguage
import com.vibethroughcode.ftree.ui.common.isShortWindow
import com.vibethroughcode.ftree.ui.nearby.NearbyScreen
import com.vibethroughcode.ftree.ui.network.ContactsScreen
import com.vibethroughcode.ftree.ui.network.PairingScreen
import com.vibethroughcode.ftree.ui.network.PathScreen
import com.vibethroughcode.ftree.ui.nearby.QrPanel
import com.vibethroughcode.ftree.ui.nearby.ScanAction
import com.vibethroughcode.ftree.ui.nearby.NearbyViewModel
import com.vibethroughcode.ftree.ui.people.PeopleScreen
import com.vibethroughcode.ftree.ui.person.PersonDetailScreen
import com.vibethroughcode.ftree.ui.person.PersonEditScreen
import com.vibethroughcode.ftree.ui.relative.AddRelativeScreen
import com.vibethroughcode.ftree.ui.settings.SettingsScreen
import com.vibethroughcode.ftree.ui.settings.SettingsViewModel
import com.vibethroughcode.ftree.ui.transfer.ImportReviewScreen
import com.vibethroughcode.ftree.ui.transfer.TransferMessages
import com.vibethroughcode.ftree.ui.common.peopleCount
import com.vibethroughcode.ftree.ui.transfer.TransferViewModel
import com.vibethroughcode.ftree.ui.transfer.defaultExportName
import com.vibethroughcode.ftree.ui.book.BookScreen
import com.vibethroughcode.ftree.ui.tree.TreeScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.reflect.KClass

/** Nothing was handed to the app: the ordinary case, and a single instance rather than a new one
 * per composition, which would restart the collection on every recomposition. */
private val nothingOpened: StateFlow<Uri?> = MutableStateFlow<Uri?>(null).asStateFlow()

/** A tapped birthday note: the one person it named, or null when it named several. */
data class ReminderTap(val personId: String?)

private val noReminder: StateFlow<ReminderTap?> = MutableStateFlow<ReminderTap?>(null).asStateFlow()

const val NavTreeTag = "nav-tree"
const val NavPeopleTag = "nav-people"
const val NavNetworkTag = "nav-network"
const val NavSettingsTag = "nav-settings"

/** The places the app is, rather than the places it can go. */
private data class Destination(
    val route: Any,
    val type: KClass<*>,
    val icon: ImageVector,
    val label: Int,
    val tag: String,
)

private val destinations = listOf(
    Destination(TreeRoute(), TreeRoute::class, Icons.Default.AccountTree, R.string.nav_tree, NavTreeTag),
    Destination(PeopleRoute, PeopleRoute::class, Icons.AutoMirrored.Filled.List, R.string.nav_people, NavPeopleTag),
    Destination(NetworkRoute, NetworkRoute::class, Icons.Default.Group, R.string.nav_network, NavNetworkTag),
    Destination(SettingsRoute, SettingsRoute::class, Icons.Default.Settings, R.string.nav_settings, NavSettingsTag),
)

/**
 * The app shell.
 *
 * The three top-level places sit in a navigation bar rather than behind icons and an overflow menu.
 * The people list and the settings were previously reachable only from the chart's app bar, which
 * made two of the app's three halves feel like accessories to the third.
 *
 * Import and export live here rather than on any one screen: a transfer is an operation on the
 * whole tree, its result belongs in an app-level snackbar, and the import review has to survive
 * whichever screen started it.
 *
 * [opened] is a file another app handed over — someone tapping a `.ftree` in their downloads, or
 * choosing this app from a chat app's share sheet. It goes through exactly the same review as a
 * file picked from inside the app, because arriving from outside says nothing about whether it
 * should be trusted, and the reader still has to see what it would do before it does it.
 */
@Composable
fun FTreeApp(
    opened: StateFlow<Uri?> = nothingOpened,
    onOpened: () -> Unit = {},
    reminder: StateFlow<ReminderTap?> = noReminder,
    onReminderFollowed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    // Named once here and read by every screen that says a relationship out loud.
    val kinshipLanguage by (LocalContext.current.applicationContext as FTreeApplication)
        .container.kinshipPreferences.language.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val transferViewModel: TransferViewModel = viewModel(factory = FTreeViewModels.Factory)
    // Activity-scoped, like the transfer view model, so a rotation does not hang up a transfer.
    val nearbyViewModel: NearbyViewModel = viewModel(factory = FTreeViewModels.Factory)

    TransferMessages(transferViewModel, snackbarHostState)

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(TreeDocument.MIME_TYPE)
    ) { uri -> uri?.let(transferViewModel::export) }

    // Any type is accepted: providers disagree about what a .ftree file is, and refusing to show
    // the user's own export because a provider called it octet-stream would be absurd. The file
    // itself is validated on read.
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(transferViewModel::prepareImport) }

    /*
     * Handing one person's family to another app.
     *
     * The chooser cannot open until the file exists, so the view model writes it first and this
     * watches for it. Everything user-facing is assembled here rather than in the view model: the
     * message is a string resource that has to follow the reader's configuration, and an Intent is
     * not something a view model should know how to build.
     */
    val shared by transferViewModel.share.collectAsStateWithLifecycle()
    val siteUrl = BuildConfig.SITE_URL
    val sharedName = shared?.personName
    val sharedCount = shared?.people?.let { peopleCount(it) }
    val shareMessage = when {
        sharedCount == null -> null
        sharedName == null -> stringResource(R.string.share_message_unnamed, sharedCount, siteUrl)
        else -> stringResource(R.string.share_message, sharedName, sharedCount, siteUrl)
    }
    val shareTitle = sharedName?.let { stringResource(R.string.share_chooser_title, it) }
    LaunchedEffect(shared) {
        val branch = shared ?: return@LaunchedEffect
        val send = sendBranchIntent(branch.uri, shareMessage, shareTitle)
        runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }
        transferViewModel.clearShare()
    }

    /*
     * A file opened from outside, shown as a proposal rather than applied.
     *
     * Cleared as soon as it is handed over, so that a rotation — or coming back to the app later —
     * does not read the same file again over the top of whatever is happening now.
     */
    val openedFile by opened.collectAsStateWithLifecycle()
    LaunchedEffect(openedFile) {
        val file = openedFile ?: return@LaunchedEffect
        transferViewModel.prepareImport(file)
        onOpened()
    }

    /*
     * A birthday note, tapped: the person it names, over People so that back leads somewhere that
     * makes sense, or People itself when the note was about several.
     */
    val tappedReminder by reminder.collectAsStateWithLifecycle()
    LaunchedEffect(tappedReminder) {
        val tap = tappedReminder ?: return@LaunchedEffect
        navController.switchTo(PeopleRoute)
        tap.personId?.let { navController.navigate(PersonRoute(it)) }
        onReminderFollowed()
    }

    // People's bell asks Settings to bring the reminders into view once it opens.
    var focusReminders by remember { mutableStateOf(false) }
    val remindersOn by (LocalContext.current.applicationContext as FTreeApplication)
        .container.reminderPreferences.enabled.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val onTopLevel = destinations.any { destination?.hasRoute(it.type) == true }

    /*
     * The three places move to the side when the window is short.
     *
     * A bottom bar costs eighty of the roughly three hundred and sixty density-independent pixels a
     * phone has on its side, and it takes them from the axis a family tree is read along. A rail
     * costs width instead, which is the axis that has just doubled. Same three destinations, same
     * order, same labels — only the edge they sit on changes.
     */
    val useRail = onTopLevel && isShortWindow()

    CompositionLocalProvider(LocalKinshipLanguage provides kinshipLanguage) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (onTopLevel && !useRail) {
                    NavigationBar {
                        destinations.forEach { item ->
                            val selected = destination?.hasRoute(item.type) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.switchTo(item.route) },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(stringResource(item.label)) },
                                modifier = Modifier.testTag(item.tag),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (useRail) {
                    // Held to the standard rail width. Left to size itself it takes a quarter of a
                // landscape phone, which is the opposite of the point of moving off the bottom edge.
                NavigationRail(modifier = Modifier.fillMaxHeight().width(112.dp)) {
                        destinations.forEach { item ->
                            val selected = destination?.hasRoute(item.type) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navController.switchTo(item.route) },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(stringResource(item.label)) },
                                modifier = Modifier.testTag(item.tag),
                            )
                        }
                    }
                }
                NavHost(
                    navController = navController,
                    startDestination = TreeRoute(),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    composable<TreeRoute> { entry ->
                        TreeScreen(
                            relateFrom = entry.toRoute<TreeRoute>().relateFrom,
                            onOpenPerson = { navController.navigate(PersonRoute(it)) },
                            onAddPerson = { navController.navigate(EditPersonRoute()) },
                            onAddRelative = { anchorId, kind ->
                                navController.navigate(AddRelativeRoute(anchorId, kind))
                            },
                            onShare = transferViewModel::shareBranch,
                            onBook = { navController.navigate(BookRoute(scopePersonId = it)) },
                        )
                    }

                    composable<BookRoute> {
                        BookScreen(onBack = { navController.popBackStack() })
                    }

                    composable<PeopleRoute> {
                        PeopleScreen(
                            onOpenPerson = { navController.navigate(PersonRoute(it)) },
                            onAddPerson = { navController.navigate(EditPersonRoute()) },
                            remindersOn = remindersOn,
                            onReminders = {
                                focusReminders = true
                                navController.switchTo(SettingsRoute)
                            },
                        )
                    }

                    composable<NetworkRoute> {
                        ContactsScreen(
                            onPair = { navController.navigate(PairRoute) },
                            onOpenContact = { navController.navigate(PathRoute(it)) },
                        )
                    }

                    composable<PathRoute> {
                        PathScreen(
                            onBack = { navController.popBackStack() },
                            onTree = { personId ->
                                navController.navigate(TreeRoute(focusId = personId)) {
                                    popUpTo<TreeRoute> { inclusive = true }
                                }
                            },
                        )
                    }

                    composable<PairRoute> {
                        PairingScreen(onClose = { navController.popBackStack() })
                    }

                    composable<SettingsRoute> {
                        val settingsViewModel: SettingsViewModel =
                            viewModel(factory = FTreeViewModels.Factory)
                        SettingsScreen(
                            onExport = { exportPicker.launch(defaultExportName()) },
                            onImport = { importPicker.launch(arrayOf("*/*")) },
                            onNearby = nearbyViewModel::open,
                            viewModel = settingsViewModel,
                            focusReminders = focusReminders,
                            onRemindersFocused = { focusReminders = false },
                        )
                    }

                    composable<PersonRoute> {
                        PersonDetailScreen(
                            onBack = { navController.popBackStack() },
                            onEdit = { navController.navigate(EditPersonRoute(it)) },
                            onOpenPerson = { navController.navigate(PersonRoute(it)) },
                            onAddRelative = { anchorId, kind ->
                                navController.navigate(AddRelativeRoute(anchorId, kind))
                            },
                            onShowOnTree = { personId ->
                                navController.navigate(TreeRoute(focusId = personId)) {
                                    popUpTo<TreeRoute> { inclusive = true }
                                }
                            },
                            onRelate = { personId ->
                                navController.navigate(TreeRoute(relateFrom = personId)) {
                                    popUpTo<TreeRoute> { inclusive = true }
                                }
                            },
                            onShare = transferViewModel::shareBranch,
                        )
                    }

                    composable<AddRelativeRoute> {
                        AddRelativeScreen(onBack = { navController.popBackStack() })
                    }

                    composable<EditPersonRoute> { entry ->
                        val route = entry.toRoute<EditPersonRoute>()
                        PersonEditScreen(
                            onBack = { navController.popBackStack() },
                            onSaved = { personId ->
                                if (route.personId == null) {
                                    // A newly added person opens straight onto their own page, which is
                                    // where the next thing you want to do — add a relative — lives. Only
                                    // the form is dropped from the back stack, so going back returns to
                                    // wherever the add started rather than always to the chart.
                                    navController.navigate(PersonRoute(personId)) {
                                        popUpTo<EditPersonRoute> { inclusive = true }
                                    }
                                } else {
                                    navController.popBackStack()
                                }
                            },
                        )
                    }
                }
            }
        }

        /*
         * Nearby sharing, over whatever opened it, for the same reason as the import review below:
         * a live connection cannot be put in a route, and backing out has to hang up.
         *
         * What arrives is handed to the review through the very call the file picker uses. Nothing
         * here decides anything about anybody's family; this only delivers the bytes to the code
         * that already does.
         */
        val nearbyMode by nearbyViewModel.mode.collectAsStateWithLifecycle()
        val nearbyState by nearbyViewModel.state.collectAsStateWithLifecycle()
        LaunchedEffect(nearbyState) {
            if (nearbyState is NearbyState.Arrived) {
                nearbyViewModel.handOff { file ->
                    transferViewModel.prepareImport(Uri.fromFile(file), nearbyViewModel::importPrepared)
                }
            }
        }
        nearbyMode?.let { mode ->
            val peers by nearbyViewModel.peers.collectAsStateWithLifecycle()
            val listening by nearbyViewModel.listening.collectAsStateWithLifecycle()
            val preparing by nearbyViewModel.preparing.collectAsStateWithLifecycle()
            val pairing by nearbyViewModel.pairing.collectAsStateWithLifecycle()
            val nearbyDeviceName by nearbyViewModel.deviceName.collectAsStateWithLifecycle()
            Dialog(
                onDismissRequest = nearbyViewModel::close,
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                NearbyScreen(
                    mode = mode,
                    state = nearbyState,
                    peers = peers,
                    listening = listening,
                    deviceName = nearbyDeviceName,
                    preparing = preparing,
                    onSend = nearbyViewModel::send,
                    onSendTo = nearbyViewModel::sendTo,
                    onConfirmCode = nearbyViewModel::confirmCode,
                    onAccept = nearbyViewModel::accept,
                    onDecline = nearbyViewModel::decline,
                    onCancel = nearbyViewModel::cancel,
                    onDismiss = nearbyViewModel::dismiss,
                    onClose = nearbyViewModel::close,
                    qrPanel = { pairing?.let { QrPanel(it) } },
                    scanAction = { ScanAction(onScanned = nearbyViewModel::sendByLink) },
                )
            }
        }

        // Shown over whatever started it rather than as a navigation destination: the plan is a live
        // object that cannot be handed through a route, and backing out must leave the tree untouched.
        val importPlan by transferViewModel.plan.collectAsStateWithLifecycle()
        val importDecisions by transferViewModel.decisions.collectAsStateWithLifecycle()
        val allPeople by transferViewModel.localPeople.collectAsStateWithLifecycle()

        importPlan?.let { plan ->
            Dialog(
                onDismissRequest = transferViewModel::cancelImport,
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    // Fills the screen properly instead of leaving the scrim showing behind the status
                    // and navigation bars.
                    decorFitsSystemWindows = false,
                ),
            ) {
                ImportReviewScreen(
                    plan = plan,
                    decisions = importDecisions,
                    localPeople = allPeople,
                    onDecision = transferViewModel::setDecision,
                    onConfirm = transferViewModel::confirmImport,
                    onCancel = transferViewModel::cancelImport,
                )
            }
        }
    }
}

/**
 * Moves between the three top-level places without stacking them.
 *
 * Saving and restoring state means the chart keeps its pan and zoom, and the people list its scroll
 * position, when you step away to settings and back.
 */
private fun NavHostController.switchTo(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
