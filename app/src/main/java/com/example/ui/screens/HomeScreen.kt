package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneCallback
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.CallManager
import com.example.data.ChatRepository
import com.example.model.CallLogItem
import com.example.model.CallType
import com.example.model.ChatMessage
import com.example.model.MediaType
import com.example.model.User
import com.example.ui.components.UserAvatar
import com.example.ui.theme.CallGreen
import com.example.ui.theme.CallRed
import com.example.ui.theme.DataSaverCyan
import com.example.ui.theme.IndigoPrimary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: ChatRepository,
    callManager: CallManager,
    onOpenChat: (User) -> Unit,
    onStartCall: (User, CallType) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Chats, 1: Calls, 2: Contacts, 3: Settings
    var showAddContactDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val currentUser by repository.currentUser.collectAsState()
    val contacts by repository.contacts.collectAsState()
    val messagesMap by repository.messages.collectAsState()
    val callLogs by repository.callLogs.collectAsState()

    val lowDataCalls by repository.lowDataCallsEnabled.collectAsState()
    val lowLatency by repository.lowLatencyOptimization.collectAsState()
    val totalSavedMb by repository.totalDataSavedMb.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = when (selectedTab) {
                                0 -> "Just Chat"
                                1 -> "Call Logs"
                                2 -> "Gmail Contacts"
                                else -> "Settings & Data"
                            },
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "${currentUser.name} (${currentUser.gmail})",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAddContactDialog = true },
                        modifier = Modifier.testTag("top_add_contact_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Email,
                            contentDescription = "Find by Gmail",
                            tint = IndigoPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = "Chats") },
                    label = { Text("Chats") },
                    modifier = Modifier.testTag("tab_chats")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Rounded.Call, contentDescription = "Calls") },
                    label = { Text("Calls") },
                    modifier = Modifier.testTag("tab_calls")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Rounded.Group, contentDescription = "Contacts") },
                    label = { Text("Contacts") },
                    modifier = Modifier.testTag("tab_contacts")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    modifier = Modifier.testTag("tab_settings")
                )
            }
        },
        floatingActionButton = {
            if (selectedTab == 0 || selectedTab == 2) {
                FloatingActionButton(
                    onClick = { showAddContactDialog = true },
                    containerColor = IndigoPrimary,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("fab_add_contact")
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add Contact via Gmail")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            when (selectedTab) {
                0 -> ChatsTab(
                    contacts = contacts,
                    messagesMap = messagesMap,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onContactClick = onOpenChat,
                    onStartCall = onStartCall
                )
                1 -> CallsTab(
                    callLogs = callLogs,
                    onCallContact = { contact, type -> onStartCall(contact, type) },
                    onSimulateIncoming = { contact, type ->
                        callManager.receiveIncomingCall(contact, type, lowDataCalls)
                    }
                )
                2 -> ContactsTab(
                    contacts = contacts,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onContactClick = onOpenChat,
                    onStartCall = onStartCall,
                    onFindGmailClick = { showAddContactDialog = true }
                )
                3 -> SettingsTab(
                    currentUser = currentUser,
                    lowDataCalls = lowDataCalls,
                    onToggleLowDataCalls = { repository.lowDataCallsEnabled.value = it },
                    lowLatency = lowLatency,
                    onToggleLowLatency = { repository.lowLatencyOptimization.value = it },
                    totalSavedMb = totalSavedMb,
                    onSignOut = onSignOut
                )
            }
        }

        if (showAddContactDialog) {
            AddContactDialog(
                onDismiss = { showAddContactDialog = false },
                onAddContact = { gmail, name ->
                    val result = repository.addContactByGmail(gmail, name)
                    result.onSuccess { newContact ->
                        onOpenChat(newContact)
                    }
                }
            )
        }
    }
}

@Composable
private fun ChatsTab(
    contacts: List<User>,
    messagesMap: Map<String, List<ChatMessage>>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onContactClick: (User) -> Unit,
    onStartCall: (User, CallType) -> Unit
) {
    val filteredContacts = contacts.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
                it.gmail.contains(searchQuery, ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Search bar
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search by name or @gmail.com") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("chats_search_field")
            )
        }

        // Low-data & no-lag banner
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = DataSaverCyan.copy(alpha = 0.12f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = null,
                        tint = DataSaverCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Zero-Lag Engine Active",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Audio & video calls use 75% less data with ultra-low latency.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(filteredContacts, key = { it.id }) { contact ->
            val chatMessages = messagesMap[contact.id] ?: emptyList()
            val lastMessage = chatMessages.lastOrNull()

            ChatItemRow(
                contact = contact,
                lastMessage = lastMessage,
                onClick = { onContactClick(contact) },
                onStartAudio = { onStartCall(contact, CallType.AUDIO) },
                onStartVideo = { onStartCall(contact, CallType.VIDEO) }
            )
        }
    }
}

@Composable
private fun ChatItemRow(
    contact: User,
    lastMessage: ChatMessage?,
    onClick: () -> Unit,
    onStartAudio: () -> Unit,
    onStartVideo: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )

                if (lastMessage != null) {
                    Text(
                        text = formatRecentTime(lastMessage.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            val previewText = when {
                lastMessage == null -> contact.statusMessage
                lastMessage.mediaType == MediaType.IMAGE -> "📷 Photo shared"
                lastMessage.mediaType == MediaType.VIDEO -> "🎥 Video shared"
                lastMessage.mediaType == MediaType.VOICE -> "🎙️ Voice note (${lastMessage.mediaDurationSeconds}s)"
                else -> lastMessage.text
            }

            Text(
                text = previewText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                maxLines = 1
            )
        }

        // Quick Call & Video Actions
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onStartAudio) {
                Icon(
                    imageVector = Icons.Rounded.Call,
                    contentDescription = "Quick Audio Call",
                    tint = IndigoPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onStartVideo) {
                Icon(
                    imageVector = Icons.Rounded.Videocam,
                    contentDescription = "Quick Video Call",
                    tint = IndigoPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun CallsTab(
    callLogs: List<CallLogItem>,
    onCallContact: (User, CallType) -> Unit,
    onSimulateIncoming: (User, CallType) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Quick Simulator Card
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Test Incoming Call Flow",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Simulate receiving a zero-lag call on this device:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                if (callLogs.isNotEmpty()) {
                                    onSimulateIncoming(callLogs.first().contact, CallType.VIDEO)
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.Videocam, contentDescription = null, tint = IndigoPrimary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Simulate Video Call")
                        }

                        TextButton(
                            onClick = {
                                if (callLogs.isNotEmpty()) {
                                    onSimulateIncoming(callLogs.first().contact, CallType.AUDIO)
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.Call, contentDescription = null, tint = CallGreen)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Simulate Audio Call")
                        }
                    }
                }
            }
        }

        if (callLogs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No calls made yet. Tap audio or video call icons on any contact!",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            }
        }

        items(callLogs, key = { it.id }) { log ->
            CallLogRow(
                log = log,
                onRedial = { onCallContact(log.contact, log.callType) }
            )
        }
    }
}

@Composable
private fun CallLogRow(
    log: CallLogItem,
    onRedial: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRedial() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            name = log.contact.name,
            avatarColorHex = log.contact.avatarColorHex,
            size = 46.dp,
            showOnlineIndicator = false
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = log.contact.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val icon = when {
                    log.isMissed -> Icons.AutoMirrored.Rounded.CallMissed
                    log.isIncoming -> Icons.AutoMirrored.Rounded.CallReceived
                    else -> Icons.Rounded.CallMade
                }
                val iconColor = if (log.isMissed) CallRed else CallGreen

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(16.dp)
                )

                val callDesc = if (log.isMissed) "Missed" else if (log.durationSeconds > 0) "${log.durationSeconds / 60}m ${log.durationSeconds % 60}s" else "Connected"
                Text(
                    text = "$callDesc • ${formatRecentTime(log.timestamp)}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                if (log.wasLowDataMode && log.dataUsedKb > 0) {
                    Text(
                        text = "• ${log.dataUsedKb / 1024}MB (Data Saver)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = DataSaverCyan
                        )
                    )
                }

                if (log.wasEncrypted) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "E2EE",
                        tint = CallGreen,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        IconButton(onClick = onRedial) {
            Icon(
                imageVector = if (log.callType == CallType.VIDEO) Icons.Rounded.Videocam else Icons.Rounded.Call,
                contentDescription = "Redial",
                tint = IndigoPrimary
            )
        }
    }
}

@Composable
private fun ContactsTab(
    contacts: List<User>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onContactClick: (User) -> Unit,
    onStartCall: (User, CallType) -> Unit,
    onFindGmailClick: () -> Unit
) {
    val filtered = contacts.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
                it.gmail.contains(searchQuery, ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            // Find by Gmail banner
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = IndigoPrimary.copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable { onFindGmailClick() }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(IndigoPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Email,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Find & Connect by Gmail",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Type any friend's Gmail to connect instantly",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = IndigoPrimary
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search Gmail contacts...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        items(filtered, key = { it.id }) { contact ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onContactClick(contact) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UserAvatar(
                    name = contact.name,
                    avatarColorHex = contact.avatarColorHex,
                    size = 48.dp,
                    isOnline = contact.isOnline
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contact.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = contact.gmail,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = IndigoPrimary
                        )
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { onStartCall(contact, CallType.AUDIO) }) {
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = "Audio Call",
                            tint = IndigoPrimary
                        )
                    }
                    IconButton(onClick = { onStartCall(contact, CallType.VIDEO) }) {
                        Icon(
                            imageVector = Icons.Rounded.Videocam,
                            contentDescription = "Video Call",
                            tint = IndigoPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    currentUser: User,
    lowDataCalls: Boolean,
    onToggleLowDataCalls: (Boolean) -> Unit,
    lowLatency: Boolean,
    onToggleLowLatency: (Boolean) -> Unit,
    totalSavedMb: Float,
    onSignOut: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // User Profile Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                UserAvatar(
                    name = currentUser.name,
                    avatarColorHex = currentUser.avatarColorHex,
                    size = 56.dp,
                    isOnline = true
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentUser.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = currentUser.gmail,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = IndigoPrimary
                        )
                    )
                    Text(
                        text = currentUser.statusMessage,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }

        // Data & Calling Engine Settings
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "No-Lag & Data Saver Engine",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )

                // Low data toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Low Data Usage on Calls",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Reduces audio & video call bandwidth by ~75% using AV1 and Opus codecs",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = lowDataCalls,
                        onCheckedChange = onToggleLowDataCalls,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = IndigoPrimary)
                    )
                }

                // Zero-lag toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Zero-Lag Low-Latency Optimization",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Direct P2P jitter buffer keeps latency under 20ms",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = lowLatency,
                        onCheckedChange = onToggleLowLatency,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = CallGreen)
                    )
                }

                // Total data saved display
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DataSaverCyan.copy(alpha = 0.1f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DataUsage,
                        contentDescription = null,
                        tint = DataSaverCyan,
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = "Total Data Saved",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        Text(
                            text = "%.1f MB".format(totalSavedMb),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = DataSaverCyan
                            )
                        )
                    }
                }
            }
        }

        // Account actions
        TextButton(
            onClick = onSignOut,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Switch Gmail Account / Sign Out", color = CallRed)
        }
    }
}

private fun formatRecentTime(timeMillis: Long): String {
    val diff = System.currentTimeMillis() - timeMillis
    return when {
        diff < 60000 -> "Just now"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timeMillis))
    }
}
