package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.CallLogItem
import com.example.model.CallType
import com.example.model.User
import com.example.ui.components.UserAvatar
import com.example.ui.theme.CallGreen
import com.example.ui.theme.CallRed
import com.example.ui.theme.DataSaverCyan
import com.example.viewmodel.UiState
import com.example.viewmodel.UserSearchUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Chats : Screen("chats", "Chats", Icons.AutoMirrored.Filled.Chat)
    data object Calls : Screen("calls", "Calls", Icons.Filled.Call)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(
    currentUser: User,
    contactsState: UiState<List<User>>,
    callLogsState: UiState<List<CallLogItem>>,
    lowDataCalls: Boolean,
    lowLatency: Boolean,
    totalSavedMb: Float,
    actionError: String?,
    onDismissActionError: () -> Unit,
    onOpenChat: (User) -> Unit,
    onStartCall: (User, CallType) -> Unit,
    onSimulateIncomingCall: (User, CallType) -> Unit,
    onAddContact: (gmail: String, name: String?, onAdded: (User) -> Unit) -> Unit,
    onToggleLowDataCalls: (Boolean) -> Unit,
    onToggleLowLatency: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    userSearchState: UserSearchUiState = UserSearchUiState.Idle,
    onOpenSearchUser: () -> Unit = {},
    onSearchUserByEmail: (String) -> Unit = {},
    onClearUserSearch: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedScreen by remember { mutableStateOf<Screen>(Screen.Chats) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val items = remember { listOf(Screen.Chats, Screen.Calls) }

    LaunchedEffect(actionError) {
        if (actionError != null) {
            snackbarHostState.showSnackbar(actionError)
            onDismissActionError()
        }
    }

    BackHandler(enabled = selectedScreen != Screen.Chats) {
        selectedScreen = Screen.Chats
    }

    val loadedContacts = (contactsState as? UiState.Success)?.data.orEmpty()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Just Chat",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                        if (currentUser.gmail.isNotBlank()) {
                            Text(
                                text = currentUser.gmail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenSearchUser,
                        modifier = Modifier.testTag("dashboard_search_user_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PersonSearch,
                            contentDescription = "Find Person by Gmail"
                        )
                    }
                    IconButton(
                        onClick = { showAddContactDialog = true },
                        modifier = Modifier.testTag("dashboard_add_contact_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PersonAdd,
                            contentDescription = "Add Contact via Gmail"
                        )
                    }
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier.testTag("dashboard_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings and Account"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = selectedScreen == screen,
                        onClick = { selectedScreen = screen },
                        modifier = Modifier.testTag("nav_tab_${screen.route}")
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedScreen is Screen.Chats) {
                FloatingActionButton(
                    onClick = { showAddContactDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("fab_start_chat")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Start new conversation"
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            AnimatedContent(
                targetState = selectedScreen,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "dashboardTabTransition"
            ) { screen ->
                when (screen) {
                    is Screen.Chats -> when (contactsState) {
                        UiState.Loading -> DashboardLoadingState()
                        is UiState.Error -> DashboardErrorState(message = contactsState.message)
                        is UiState.Success -> ChatListContent(
                            contacts = contactsState.data,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            onContactClick = onOpenChat,
                            onStartCall = onStartCall,
                            onAddContactClick = { showAddContactDialog = true }
                        )
                    }

                    is Screen.Calls -> when (callLogsState) {
                        UiState.Loading -> DashboardLoadingState()
                        is UiState.Error -> DashboardErrorState(message = callLogsState.message)
                        is UiState.Success -> CallHistoryContent(
                            callLogs = callLogsState.data,
                            contacts = loadedContacts,
                            onCallContact = onStartCall,
                            onSimulateIncoming = onSimulateIncomingCall
                        )
                    }
                }
            }
        }
    }

    if (showAddContactDialog) {
        AddContactDialog(
            onDismiss = { showAddContactDialog = false },
            onAddContact = { gmail, name ->
                onAddContact(gmail, name) { newContact ->
                    onOpenChat(newContact)
                }
            },
            userSearchState = userSearchState,
            onSearchUserByEmail = onSearchUserByEmail,
            onClearUserSearch = onClearUserSearch
        )
    }

    if (showSettingsDialog) {
        DashboardSettingsDialog(
            currentUser = currentUser,
            lowDataCalls = lowDataCalls,
            onToggleLowDataCalls = onToggleLowDataCalls,
            lowLatency = lowLatency,
            onToggleLowLatency = onToggleLowLatency,
            totalSavedMb = totalSavedMb,
            onSignOut = {
                showSettingsDialog = false
                onSignOut()
            },
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
fun ChatListContent(
    contacts: List<User> = emptyList(),
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    onContactClick: (User) -> Unit = {},
    onStartCall: (User, CallType) -> Unit = { _, _ -> },
    onAddContactClick: () -> Unit = {}
) {
    val filteredContacts = remember(contacts, searchQuery) {
        if (searchQuery.isBlank()) {
            contacts
        } else {
            contacts.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.gmail.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    if (contacts.isEmpty()) {
        DashboardEmptyState(
            icon = Icons.AutoMirrored.Filled.Chat,
            message = "No chats yet. Start a conversation!",
            tip = "Add a friend using their Gmail address to send text messages or start a call.",
            actionLabel = "Add Contact",
            onAction = onAddContactClick
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "chat_search_bar") {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search chats by name or Gmail") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "Search chats") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("chats_search_field")
            )
        }

        item(key = "low_data_status_banner") {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = "Cloud Sync & Low-Data Ready",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Your chats are synced to Firestore with low-data calling enabled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (filteredContacts.isEmpty()) {
            item(key = "no_search_matches") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No chats matching \"$searchQuery\"",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            items(filteredContacts, key = { it.id }) { contact ->
                DashboardChatRow(
                    contact = contact,
                    onClick = { onContactClick(contact) },
                    onAudioCall = { onStartCall(contact, CallType.AUDIO) },
                    onVideoCall = { onStartCall(contact, CallType.VIDEO) }
                )
            }
        }
    }
}

@Composable
fun CallHistoryContent(
    callLogs: List<CallLogItem> = emptyList(),
    contacts: List<User> = emptyList(),
    onCallContact: (User, CallType) -> Unit = { _, _ -> },
    onSimulateIncoming: (User, CallType) -> Unit = { _, _ -> }
) {
    val quickContact = callLogs.firstOrNull()?.contact ?: contacts.firstOrNull()

    if (callLogs.isEmpty() && quickContact == null) {
        DashboardEmptyState(
            icon = Icons.Filled.Call,
            message = "No recent calls",
            tip = "Add a contact in Chats and start an audio or video call to see your call history here."
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (quickContact != null) {
            item(key = "incoming_call_test_card") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Test Incoming Call",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Try receiving a low-latency call from ${quickContact.name}:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { onSimulateIncoming(quickContact, CallType.VIDEO) },
                                modifier = Modifier.testTag("simulate_video_call_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Videocam,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Video Call")
                            }
                            TextButton(
                                onClick = { onSimulateIncoming(quickContact, CallType.AUDIO) },
                                modifier = Modifier.testTag("simulate_audio_call_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Call,
                                    contentDescription = null,
                                    tint = CallGreen
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Audio Call")
                            }
                        }
                    }
                }
            }
        }

        if (callLogs.isEmpty()) {
            item(key = "empty_call_logs_notice") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No recent calls",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(callLogs, key = { it.id }) { log ->
                DashboardCallLogRow(
                    log = log,
                    onRedial = { onCallContact(log.contact, log.callType) }
                )
            }
        }
    }
}

@Composable
private fun DashboardLoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun DashboardErrorState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun DashboardChatRow(
    contact: User,
    onClick: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("chat_item_${contact.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            name = contact.name,
            avatarColorHex = contact.avatarColorHex,
            size = 52.dp,
            isOnline = contact.isOnline
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = contact.gmail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(
                onClick = onAudioCall,
                modifier = Modifier.testTag("chat_audio_call_${contact.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Call,
                    contentDescription = "Audio call ${contact.name}",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = onVideoCall,
                modifier = Modifier.testTag("chat_video_call_${contact.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Videocam,
                    contentDescription = "Video call ${contact.name}",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun DashboardCallLogRow(
    log: CallLogItem,
    onRedial: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRedial)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("call_log_${log.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            name = log.contact.name,
            avatarColorHex = log.contact.avatarColorHex,
            size = 48.dp,
            showOnlineIndicator = false
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = log.contact.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val directionIcon = when {
                    log.isMissed -> Icons.AutoMirrored.Rounded.CallMissed
                    log.isIncoming -> Icons.AutoMirrored.Rounded.CallReceived
                    else -> Icons.AutoMirrored.Rounded.CallMade
                }
                Icon(
                    imageVector = directionIcon,
                    contentDescription = null,
                    tint = if (log.isMissed) CallRed else CallGreen,
                    modifier = Modifier.size(16.dp)
                )

                val durationLabel = when {
                    log.isMissed -> "Missed"
                    log.durationSeconds > 0 -> "${log.durationSeconds / 60}m ${log.durationSeconds % 60}s"
                    else -> "Connected"
                }

                Text(
                    text = "$durationLabel • ${formatDashboardTime(log.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (log.wasEncrypted) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "End-to-end encrypted",
                        tint = CallGreen,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            if (log.wasLowDataMode && log.dataUsedKb > 0) {
                Text(
                    text = "${"%.1f".format(log.dataUsedKb / 1024f)} MB • Data Saver",
                    style = MaterialTheme.typography.labelSmall,
                    color = DataSaverCyan
                )
            }
        }

        IconButton(
            onClick = onRedial,
            modifier = Modifier.testTag("redial_button_${log.id}")
        ) {
            Icon(
                imageVector = if (log.callType == CallType.VIDEO) Icons.Rounded.Videocam else Icons.Rounded.Call,
                contentDescription = "Redial ${log.contact.name}",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun DashboardEmptyState(
    icon: ImageVector,
    message: String,
    tip: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(34.dp)
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Text(
                text = tip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onAction,
                    modifier = Modifier.testTag("empty_state_action_button")
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun DashboardSettingsDialog(
    currentUser: User,
    lowDataCalls: Boolean,
    onToggleLowDataCalls: (Boolean) -> Unit,
    lowLatency: Boolean,
    onToggleLowLatency: (Boolean) -> Unit,
    totalSavedMb: Float,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                UserAvatar(
                    name = currentUser.name,
                    avatarColorHex = currentUser.avatarColorHex,
                    size = 44.dp,
                    isOnline = true
                )
                Column {
                    Text(
                        text = currentUser.name.ifBlank { "Me" },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = currentUser.gmail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Low Data Calls",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Save up to 75% bandwidth on calls",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = lowDataCalls,
                        onCheckedChange = onToggleLowDataCalls,
                        modifier = Modifier.testTag("settings_low_data_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Low-Latency Mode",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Direct P2P routing under 20ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = lowLatency,
                        onCheckedChange = onToggleLowLatency,
                        modifier = Modifier.testTag("settings_low_latency_switch")
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DataSaverCyan.copy(alpha = 0.12f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DataUsage,
                        contentDescription = null,
                        tint = DataSaverCyan
                    )
                    Column {
                        Text(
                            text = "Total Data Saved",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "%.1f MB".format(totalSavedMb),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = DataSaverCyan
                            )
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onSignOut,
                modifier = Modifier.testTag("settings_sign_out_button")
            ) {
                Text("Sign Out", color = CallRed)
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("settings_close_button")
            ) {
                Text("Done")
            }
        }
    )
}

private fun formatDashboardTime(timeMillis: Long): String {
    val diff = System.currentTimeMillis() - timeMillis
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timeMillis))
    }
}
