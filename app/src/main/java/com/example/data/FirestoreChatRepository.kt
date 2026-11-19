package com.example.data

import android.content.Context
import com.example.R
import com.example.model.CallLogItem
import com.example.model.CallType
import com.example.model.ChatMessage
import com.example.model.MessageStatus
import com.example.model.User
import com.example.utils.buildChatRoomId
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FirestoreChatRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(expectedUserId: String? = null): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        if (expectedUserId != null) {
            check(uid == expectedUserId) { "Signed-in user changed; refusing cross-user access." }
        }
        return uid
    }

    suspend fun ensureUserProfile(user: FirebaseUser): Result<Unit> {
        val uid = user.uid
        val docPath = "users/$uid"
        return try {
            requireUserId(uid)
            val docRef = db.collection("users").document(uid)
            val existing = docRef.get().await()
            if (!existing.exists()) {
                val safeDisplayName = user.displayName
                    ?.trim()
                    ?.take(60)
                    ?.ifBlank { null }
                    ?: user.email?.substringBefore("@")?.take(60)?.ifBlank { null }
                    ?: "Just Chat User"
                val safeEmail = user.email
                    ?.trim()
                    ?.take(120)
                    ?.takeIf { it.length >= 3 }
                    ?: "user@gmail.com"

                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to safeDisplayName,
                    "email" to safeEmail,
                    "statusMessage" to "Using Just Chat",
                    "lowDataCallsEnabled" to true,
                    "lowLatencyEnabled" to true,
                    "totalDataSavedMb" to 0.0,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    fun observeUserProfile(userId: String): Flow<UserProfileDoc?> {
        val path = "users/$userId"
        return db.collection("users").document(userId)
            .snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        UserProfileDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.GET, path)
                }
                throw error
            }
    }

    fun observeContacts(userId: String): Flow<List<User>> {
        val path = "users/$userId/contacts"
        return db.collection("users").document(userId).collection("contacts")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ContactDoc::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                    .map { it.toDomainUser() }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, path)
                }
                throw error
            }
    }

    fun observeMessages(userId: String, contactId: String): Flow<List<ChatMessage>> {
        val path = "users/$userId/contacts/$contactId/messages"
        return db.collection("users")
            .document(userId)
            .collection("contacts")
            .document(contactId)
            .collection("messages")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    MessageDoc::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                    .sortedBy { it.createdAt ?: Timestamp(0, 0) }
                    .map { it.toDomainMessage() }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, path)
                }
                throw error
            }
    }

    /**
     * Observes real-time messages in the shared two-user room at
     * `/chats/{chatRoomId}/messages`, filtered by `participantEmails` to match
     * zero-trust Firestore security rules and sorted chronologically in memory
     * (ascending) without requiring a manual composite index.
     */
    fun observeSharedMessages(
        currentUserId: String,
        currentUserEmail: String,
        contactEmail: String
    ): Flow<List<ChatMessage>> {
        val normalizedMyEmail = (auth.currentUser?.email ?: currentUserEmail).trim().lowercase()
        val normalizedPeerEmail = contactEmail.trim().lowercase()
        if (!isValidEmailFormat(normalizedMyEmail) || !isValidEmailFormat(normalizedPeerEmail)) {
            return flowOf(emptyList())
        }

        val chatRoomId = buildChatRoomId(normalizedMyEmail, normalizedPeerEmail)
        val path = "chats/$chatRoomId/messages"
        return db.collection("chats")
            .document(chatRoomId)
            .collection("messages")
            .whereArrayContains("participantEmails", normalizedMyEmail)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    SharedChatMessageDoc::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                    .sortedWith(
                        compareBy<SharedChatMessageDoc> { it.createdAt ?: Timestamp(0, 0) }
                            .thenBy { it.id }
                    )
                    .map { it.toDomainMessage(currentUserId, normalizedMyEmail) }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == currentUserId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, path)
                }
                throw error
            }
    }

    fun observeCallLogs(userId: String): Flow<List<CallLogItem>> {
        val path = "users/$userId/callLogs"
        return db.collection("users").document(userId).collection("callLogs")
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    CallLogDoc::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                    .map { it.toDomainCallLog() }
            }
            .catch { error ->
                val firestoreError = unwrapFirestoreError(error)
                if (firestoreError == null && error is CancellationException) throw error
                if (auth.currentUser?.uid == userId) {
                    handleFirestoreError(firestoreError ?: error, OperationType.LIST, path)
                }
                throw error
            }
    }

    suspend fun addContactByGmail(
        userId: String,
        gmail: String,
        name: String?,
        existingContacts: List<User> = emptyList()
    ): Result<User> {
        val trimmedEmail = gmail.trim().lowercase()
        if (!trimmedEmail.contains("@") || trimmedEmail.length !in 3..120) {
            return Result.failure(
                IllegalArgumentException("Please enter a valid Gmail address (e.g. friend@gmail.com)")
            )
        }

        val existing = existingContacts.firstOrNull { it.gmail.equals(trimmedEmail, ignoreCase = true) }
        if (existing != null) {
            return Result.success(existing)
        }

        val displayName = name?.trim()?.take(80)?.ifBlank { null }
            ?: trimmedEmail.substringBefore("@")
                .replace(".", " ")
                .replaceFirstChar { it.uppercase() }
                .take(80)
                .ifBlank { "Contact" }

        val colors = listOf(
            0xFF4F46E5L,
            0xFF0EA5E9L,
            0xFF8B5CF6L,
            0xFF10B981L,
            0xFFF59E0BL,
            0xFFEC4899L
        )
        val assignedColor = colors[existingContacts.size % colors.size]
        val contactId = "contact_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val fingerprint = User.generateSafetyNumbers(trimmedEmail).take(80)
        val docPath = "users/$userId/contacts/$contactId"

        return try {
            requireUserId(userId)
            val contactRef = db.collection("users")
                .document(userId)
                .collection("contacts")
                .document(contactId)

            val payload = mapOf(
                "userId" to userId,
                "name" to displayName,
                "gmail" to trimmedEmail,
                "avatarColorHex" to assignedColor,
                "statusMessage" to "Connected via Gmail",
                "online" to true,
                "lastSeenText" to "Active now",
                "securityFingerprint" to fingerprint,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            contactRef.set(payload).await()

            Result.success(
                User(
                    id = contactId,
                    name = displayName,
                    gmail = trimmedEmail,
                    avatarColorHex = assignedColor,
                    statusMessage = "Connected via Gmail",
                    isOnline = true,
                    lastSeenText = "Active now",
                    securityFingerprint = fingerprint
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        userId: String,
        contactId: String,
        text: String
    ): Result<Unit> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty."))
        }
        val boundedText = trimmed.take(2000)
        val safeContactId = contactId.trim()
        if (safeContactId.isEmpty()) {
            return Result.failure(IllegalArgumentException("Contact ID is required."))
        }

        val messageId = "msg_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docPath = "users/$userId/contacts/$safeContactId/messages/$messageId"

        return try {
            requireUserId(userId)
            val msgRef = db.collection("users")
                .document(userId)
                .collection("contacts")
                .document(safeContactId)
                .collection("messages")
                .document(messageId)

            val payload = mapOf(
                "userId" to userId,
                "contactId" to safeContactId,
                "senderId" to "current_user",
                "receiverId" to safeContactId,
                "text" to boundedText,
                "status" to MessageStatus.SENT.name,
                "encrypted" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            msgRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleReaction(
        userId: String,
        contactId: String,
        message: ChatMessage,
        emoji: String
    ): Result<Unit> {
        if (contactId.isBlank() || message.id.isBlank() || emoji.isBlank()) {
            return Result.failure(IllegalArgumentException("Invalid reaction parameters."))
        }
        val docPath = "users/$userId/contacts/$contactId/messages/${message.id}"
        return try {
            requireUserId(userId)
            val updatedReactions = message.reactions.toMutableMap()
            if ((updatedReactions[emoji] ?: 0) > 0) {
                updatedReactions.remove(emoji)
            } else if (updatedReactions.size < 20) {
                updatedReactions[emoji] = 1
            }

            db.collection("users")
                .document(userId)
                .collection("contacts")
                .document(contactId)
                .collection("messages")
                .document(message.id)
                .update(
                    mapOf(
                        "reactions" to updatedReactions,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Sends a message into the shared two-user room `/chats/{chatRoomId}/messages/{messageId}`
     * computed via [buildChatRoomId]. Uses server timestamps and denormalized verified participant
     * emails so both users can exchange messages in real time under zero-trust security rules.
     */
    suspend fun sendSharedMessage(
        currentUserId: String,
        senderEmail: String,
        receiverEmail: String,
        text: String
    ): Result<Unit> {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty."))
        }
        val boundedText = cleanText.take(2000)

        val normalizedSender = (auth.currentUser?.email ?: senderEmail).trim().lowercase()
        val normalizedReceiver = receiverEmail.trim().lowercase()
        if (!isValidEmailFormat(normalizedSender) || !isValidEmailFormat(normalizedReceiver)) {
            return Result.failure(
                IllegalArgumentException("A valid Gmail address is required for both chat participants.")
            )
        }

        val chatRoomId = buildChatRoomId(normalizedSender, normalizedReceiver)
        val participantEmails = listOf(normalizedSender, normalizedReceiver).sorted()
        val messageId = "msg_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docPath = "chats/$chatRoomId/messages/$messageId"

        return try {
            val verifiedUid = requireUserId(currentUserId)
            val msgRef = db.collection("chats")
                .document(chatRoomId)
                .collection("messages")
                .document(messageId)

            val payload = mapOf(
                "chatRoomId" to chatRoomId,
                "senderUid" to verifiedUid,
                "senderEmail" to normalizedSender,
                "receiverEmail" to normalizedReceiver,
                "participantEmails" to participantEmails,
                "text" to boundedText,
                "status" to MessageStatus.SENT.name,
                "encrypted" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            msgRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun toggleSharedReaction(
        currentUserId: String,
        currentUserEmail: String,
        contactEmail: String,
        message: ChatMessage,
        emoji: String
    ): Result<Unit> {
        val normalizedMyEmail = (auth.currentUser?.email ?: currentUserEmail).trim().lowercase()
        val normalizedPeerEmail = contactEmail.trim().lowercase()
        if (!isValidEmailFormat(normalizedMyEmail) ||
            !isValidEmailFormat(normalizedPeerEmail) ||
            message.id.isBlank() ||
            emoji.isBlank()
        ) {
            return Result.failure(IllegalArgumentException("Invalid reaction parameters."))
        }

        val chatRoomId = buildChatRoomId(normalizedMyEmail, normalizedPeerEmail)
        val docPath = "chats/$chatRoomId/messages/${message.id}"
        return try {
            requireUserId(currentUserId)
            val updatedReactions = message.reactions.toMutableMap()
            if ((updatedReactions[emoji] ?: 0) > 0) {
                updatedReactions.remove(emoji)
            } else if (updatedReactions.size < 20) {
                updatedReactions[emoji] = 1
            }

            db.collection("chats")
                .document(chatRoomId)
                .collection("messages")
                .document(message.id)
                .update(
                    mapOf(
                        "reactions" to updatedReactions,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    /**
     * Advances delivery/read receipt status (`DELIVERED` or `READ`) on incoming messages
     * received by the current verified user in `/chats/{chatRoomId}/messages`.
     */
    suspend fun markSharedMessagesStatus(
        currentUserId: String,
        currentUserEmail: String,
        contactEmail: String,
        messageIds: List<String>,
        targetStatus: MessageStatus
    ): Result<Unit> {
        if (targetStatus != MessageStatus.DELIVERED && targetStatus != MessageStatus.READ) {
            return Result.failure(
                IllegalArgumentException("Only DELIVERED or READ status transitions are supported.")
            )
        }
        val normalizedMyEmail = (auth.currentUser?.email ?: currentUserEmail).trim().lowercase()
        val normalizedPeerEmail = contactEmail.trim().lowercase()
        val cleanIds = messageIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanIds.isEmpty()) return Result.success(Unit)
        if (!isValidEmailFormat(normalizedMyEmail) || !isValidEmailFormat(normalizedPeerEmail)) {
            return Result.failure(IllegalArgumentException("Invalid participant emails for status update."))
        }

        val chatRoomId = buildChatRoomId(normalizedMyEmail, normalizedPeerEmail)
        val collectionPath = "chats/$chatRoomId/messages"
        return try {
            requireUserId(currentUserId)
            val messagesRef = db.collection("chats")
                .document(chatRoomId)
                .collection("messages")
            val updatePayload = mapOf(
                "status" to targetStatus.name,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            cleanIds.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { messageId ->
                    batch.update(messagesRef.document(messageId), updatePayload)
                }
                batch.commit().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, collectionPath)
            Result.failure(e)
        }
    }

    suspend fun recordCompletedCall(
        userId: String,
        contact: User,
        callType: CallType,
        durationSeconds: Int,
        wasLowDataMode: Boolean,
        dataUsedKb: Long,
        currentTotalDataSavedMb: Double
    ): Result<Unit> {
        val callLogId = "call_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docPath = "users/$userId/callLogs/$callLogId"
        val safeContactId = contact.id.takeIf {
            it.isNotBlank() && it.length <= 128 && it.matches(Regex("^[a-zA-Z0-9_\\-]+$"))
        } ?: "contact_unknown"

        return try {
            requireUserId(userId)
            val boundedDuration = durationSeconds.coerceIn(0, 86_400)
            val boundedDataKb = dataUsedKb.coerceIn(0L, 100_000_000L)
            val payload = mapOf(
                "userId" to userId,
                "contactId" to safeContactId,
                "contactName" to contact.name.trim().take(80).ifBlank { "Contact" },
                "contactGmail" to contact.gmail.trim().take(120).takeIf { it.length >= 3 }.orEmpty().ifBlank { "contact@gmail.com" },
                "contactAvatarColorHex" to contact.avatarColorHex,
                "callType" to callType.name,
                "incoming" to false,
                "missed" to (boundedDuration == 0),
                "durationSeconds" to boundedDuration,
                "dataUsedKb" to boundedDataKb,
                "lowDataMode" to wasLowDataMode,
                "encrypted" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("users")
                .document(userId)
                .collection("callLogs")
                .document(callLogId)
                .set(payload)
                .await()

            if (wasLowDataMode && boundedDuration > 0) {
                val addedMb = (boundedDataKb * 2.3) / (1024.0 * 1000.0)
                val nextTotal = (currentTotalDataSavedMb + addedMb).coerceIn(0.0, 1_000_000.0)
                db.collection("users")
                    .document(userId)
                    .update(
                        mapOf(
                            "totalDataSavedMb" to nextTotal,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    .await()
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateLowDataCallsEnabled(userId: String, enabled: Boolean): Result<Unit> {
        val docPath = "users/$userId"
        return try {
            requireUserId(userId)
            db.collection("users")
                .document(userId)
                .update(
                    mapOf(
                        "lowDataCallsEnabled" to enabled,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateLowLatencyEnabled(userId: String, enabled: Boolean): Result<Unit> {
        val docPath = "users/$userId"
        return try {
            requireUserId(userId)
            db.collection("users")
                .document(userId)
                .update(
                    mapOf(
                        "lowLatencyEnabled" to enabled,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    private fun isValidEmailFormat(email: String): Boolean {
        return email.length in 3..120 && EMAIL_REGEX.matches(email)
    }

    private companion object {
        val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
