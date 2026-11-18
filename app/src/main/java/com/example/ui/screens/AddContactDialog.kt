package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.UserAvatar
import com.example.ui.theme.CallGreen
import com.example.ui.theme.IndigoPrimary
import com.example.viewmodel.UserSearchUiState

@Composable
fun AddContactDialog(
    onDismiss: () -> Unit,
    onAddContact: (gmail: String, name: String?) -> Unit,
    userSearchState: UserSearchUiState = UserSearchUiState.Idle,
    onSearchUserByEmail: ((String) -> Unit)? = null,
    onClearUserSearch: () -> Unit = {}
) {
    var gmail by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(userSearchState) {
        if (userSearchState is UserSearchUiState.Found && name.isBlank()) {
            name = userSearchState.user.displayName
        }
    }

    val handleDismiss = {
        onClearUserSearch()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = handleDismiss,
        modifier = Modifier.imePadding(),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.PersonAdd,
                    contentDescription = null,
                    tint = IndigoPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Find by Gmail",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Search for a registered Just Chat user by Gmail address to connect, message, and call.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = gmail,
                    onValueChange = {
                        gmail = it
                        errorMsg = null
                        if (userSearchState !is UserSearchUiState.Idle) {
                            onClearUserSearch()
                        }
                    },
                    label = { Text("Friend's Gmail") },
                    placeholder = { Text("friend@gmail.com") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Email, contentDescription = null, tint = IndigoPrimary)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_contact_email_input")
                )

                if (onSearchUserByEmail != null) {
                    OutlinedButton(
                        onClick = {
                            val trimmed = gmail.trim()
                            if (!isValidEmailInput(trimmed)) {
                                errorMsg = "Please enter a valid Gmail address (e.g. friend@gmail.com)"
                                return@OutlinedButton
                            }
                            errorMsg = null
                            onSearchUserByEmail(trimmed)
                        },
                        enabled = userSearchState !is UserSearchUiState.Searching && gmail.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_user_directory_button")
                    ) {
                        if (userSearchState is UserSearchUiState.Searching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Searching directory...")
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Search on Just Chat")
                        }
                    }

                    when (userSearchState) {
                        is UserSearchUiState.Found -> {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = CallGreen.copy(alpha = 0.12f)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("directory_user_found_card")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    UserAvatar(
                                        name = userSearchState.user.displayName,
                                        avatarColorHex = 0xFF4F46E5L,
                                        size = 38.dp,
                                        showOnlineIndicator = false
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = userSearchState.user.displayName,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            )
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircle,
                                                contentDescription = "Registered on Just Chat",
                                                tint = CallGreen,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                        Text(
                                            text = "Registered on Just Chat • ${userSearchState.user.email}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        is UserSearchUiState.NotFound -> {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("directory_user_not_found_card")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "No Just Chat account found for ${userSearchState.email} yet. You can still save this contact.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        is UserSearchUiState.Error -> {
                            Text(
                                text = userSearchState.message,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }

                        else -> Unit
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name (Optional)") },
                    placeholder = { Text("Auto-filled if registered") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_contact_name_input")
                )

                if (errorMsg != null) {
                    Text(
                        text = errorMsg ?: "",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = gmail.trim()
                    if (!isValidEmailInput(trimmed)) {
                        errorMsg = "Please enter a valid Gmail address (e.g. friend@gmail.com)"
                        return@Button
                    }
                    val resolvedName = name.trim().ifBlank {
                        (userSearchState as? UserSearchUiState.Found)?.user?.displayName
                    }
                    onAddContact(trimmed, resolvedName)
                    onClearUserSearch()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("confirm_add_contact_button")
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = handleDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun isValidEmailInput(email: String): Boolean {
    if (email.length !in 3..120) return false
    val parts = email.split("@")
    return parts.size == 2 &&
        parts[0].isNotBlank() &&
        parts[1].contains(".") &&
        !parts[1].startsWith(".") &&
        !parts[1].endsWith(".")
}
