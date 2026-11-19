package com.justchat.app.sync

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.example.R
import com.example.data.CallInvite
import com.example.data.CallSignaling
import com.example.data.OperationType
import com.example.data.SharedChatMessageDoc
import com.example.data.handleFirestoreError
import com.example.model.CallType
import com.example.model.MessageStatus
import com.example.utils.UserPresenceManager
import com.example.utils.buildChatRoomId as buildSharedChatRoomId
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ListenerRegistration
import java.util.UUID

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    val id: String = "",
    val sender: String = "",
    val receiver: String = "",
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = STATUS_SENT // "sent", "delivered", "read"
) {
    companion object {
        const val STATUS_SENT = "sent"
        const val STATUS_DELIVERED = "delivered"
        const val STATUS_READ = "read"
    }
}

@Keep
@IgnoreExtraProperties
data class CallSignal(
    val channelName: String = "",
    val caller: String = "",
    val callee: String = "",
    val status: String = CallInvite.STATUS_CALLING, // "calling", "accepted", "ended"
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Unified real-time sync facade for Just Chat covering:
 * 1. Online/offline user presence via Firebase Realtime Database (`presence/{uid}/status`).
 * 2. Shared two-user Firestore messaging (`/chats/{roomId}/messages/{messageId}`).
 * 3. Shared two-user Firestore call signaling (`/calls/{roomId}`) coordinated with Agora RTC.
 *
 * Always uses the app's provisioned named Firestore database (`R.string.firestore_database_id`),
 * server timestamps (`FieldValue.serverTimestamp()`), and zero-trust verified-email filters.
 */
object JustChatManager {

    private const val TAG = "JustChatManager"
    private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    private val auth: FirebaseAuth
        get() = Firebase.auth

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedFirestore: FirebaseFirestore? = null

    /**
     * Binds [JustChatManager] to the application context so Firestore operations always
     * target the configured named Firestore database (`R.string.firestore_database_id`).
     */
    fun initialize(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (cachedFirestore == null) {
            val databaseId = appCtx.getString(R.string.firestore_database_id)
            cachedFirestore = FirebaseFirestore.getInstance(databaseId)
        }
    }

    private fun resolveFirestore(): FirebaseFirestore? {
        cachedFirestore?.let { return it }
        val ctx = appContext ?: run {
            Log.w(TAG, "JustChatManager.initialize(context) must be called before Firestore operations.")
            return null
        }
        val databaseId = ctx.getString(R.string.firestore_database_id)
        return FirebaseFirestore.getInstance(databaseId).also { cachedFirestore = it }
    }

    fun buildChatRoomId(userA: String, userB: String): String =
        buildSharedChatRoomId(userA, userB)

    // 1. Real-time online/offline presence tracking & peer presence observation
    fun startPresenceTracking() {
        UserPresenceManager.setupUserPresence()
    }

    fun stopPresenceTracking() {
        UserPresenceManager.setOfflineAndDetach()
    }

    fun observeUserPresence(
        friendUid: String,
        onStatusChanged: (Boolean) -> Unit
    ): ValueEventListener? {
        return UserPresenceManager.observeUserPresence(
            friendUid = friendUid,
            onStatusChanged = onStatusChanged
        )
    }

    fun removePresenceObserver(friendUid: String, listener: ValueEventListener) {
        UserPresenceManager.removePresenceObserver(friendUid = friendUid, listener = listener)
    }

    // 2. Shared room real-time messaging (/chats/{roomId}/messages/{messageId})
    fun sendMessage(friendEmail: String, text: String) {
        val db = resolveFirestore() ?: return
        val currentUser = auth.currentUser ?: return
        val senderEmail = currentUser.email?.trim()?.lowercase().orEmpty()
        val receiverEmail = friendEmail.trim().lowercase()
        val cleanText = text.trim().take(2000)
        if (cleanText.isEmpty() || !isValidEmail(senderEmail) || !isValidEmail(receiverEmail)) return

        val roomId = buildChatRoomId(senderEmail, receiverEmail)
        val participantEmails = listOf(senderEmail, receiverEmail).sorted()
        val messageId = "msg_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docPath = "chats/$roomId/messages/$messageId"

        val payload = mapOf(
            "chatRoomId" to roomId,
            "senderUid" to currentUser.uid,
            "senderEmail" to senderEmail,
            "receiverEmail" to receiverEmail,
            "participantEmails" to participantEmails,
            "text" to cleanText,
            "status" to MessageStatus.SENT.name,
            "encrypted" to true,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        db.collection("chats")
            .document(roomId)
            .collection("messages")
            .document(messageId)
            .set(payload)
            .addOnFailureListener { error ->
                handleFirestoreError(error, OperationType.CREATE, docPath)
            }
    }

    fun observeMessages(
        friendEmail: String,
        onMessages: (List<ChatMessage>) -> Unit
    ): ListenerRegistration? {
        val db = resolveFirestore() ?: return null
        val senderEmail = auth.currentUser?.email?.trim()?.lowercase().orEmpty()
        val receiverEmail = friendEmail.trim().lowercase()
        if (!isValidEmail(senderEmail) || !isValidEmail(receiverEmail)) return null

        val roomId = buildChatRoomId(senderEmail, receiverEmail)
        val path = "chats/$roomId/messages"

        return db.collection("chats")
            .document(roomId)
            .collection("messages")
            .whereArrayContains("participantEmails", senderEmail)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    handleFirestoreError(error, OperationType.LIST, path)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val items = snapshot.toObjects(
                    SharedChatMessageDoc::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                    .sortedWith(
                        compareBy<SharedChatMessageDoc> { it.createdAt ?: Timestamp(0, 0) }
                            .thenBy { it.id }
                    )
                    .map { doc ->
                        ChatMessage(
                            id = doc.id,
                            sender = doc.senderEmail,
                            receiver = doc.receiverEmail,
                            text = doc.text,
                            timestamp = (doc.createdAt ?: doc.updatedAt ?: Timestamp.now()).toDate().time,
                            status = normalizeClientMessageStatus(doc.status)
                        )
                    }
                onMessages(items)
            }
    }

    /**
     * Marks incoming peer messages that are still `"sent"` as `"delivered"`
     * when the receiver is online/active.
     */
    fun markMessagesAsDelivered(
        friendIdOrEmail: String,
        messages: List<ChatMessage>,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        val currentUserEmail = auth.currentUser?.email?.trim()?.lowercase().orEmpty()
        if (currentUserEmail.isBlank() || friendIdOrEmail.isBlank() || messages.isEmpty()) return

        val undeliveredIds = messages
            .filter { msg ->
                !msg.sender.equals(currentUserEmail, ignoreCase = true) &&
                    msg.status.equals(ChatMessage.STATUS_SENT, ignoreCase = true) &&
                    msg.id.isNotBlank()
            }
            .map { it.id }

        if (undeliveredIds.isNotEmpty()) {
            updateMessagesStatus(friendIdOrEmail, undeliveredIds, MessageStatus.DELIVERED, onFailure)
        }
    }

    /**
     * Marks incoming peer messages as `"read"` (blue double tick) when the receiver
     * opens the conversation and views the messages.
     */
    fun markMessagesAsRead(
        friendIdOrEmail: String,
        messages: List<ChatMessage>,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        val currentUserEmail = auth.currentUser?.email?.trim()?.lowercase().orEmpty()
        if (currentUserEmail.isBlank() || friendIdOrEmail.isBlank() || messages.isEmpty()) return

        val unreadIds = messages
            .filter { msg ->
                !msg.sender.equals(currentUserEmail, ignoreCase = true) &&
                    !msg.status.equals(ChatMessage.STATUS_READ, ignoreCase = true) &&
                    msg.id.isNotBlank()
            }
            .map { it.id }

        if (unreadIds.isNotEmpty()) {
            updateMessagesStatus(friendIdOrEmail, unreadIds, MessageStatus.READ, onFailure)
        }
    }

    @JvmName("markMessageIdsAsDelivered")
    fun markMessagesAsDelivered(friendEmail: String, messageIds: List<String>) {
        updateMessagesStatus(friendEmail, messageIds, MessageStatus.DELIVERED)
    }

    @JvmName("markMessageIdsAsRead")
    fun markMessagesAsRead(friendEmail: String, messageIds: List<String>) {
        updateMessagesStatus(friendEmail, messageIds, MessageStatus.READ)
    }

    fun updateMessageStatus(friendEmail: String, messageId: String, status: String) {
        val targetStatus = when (status.trim().lowercase()) {
            ChatMessage.STATUS_DELIVERED -> MessageStatus.DELIVERED
            ChatMessage.STATUS_READ -> MessageStatus.READ
            else -> return
        }
        updateMessagesStatus(friendEmail, listOf(messageId), targetStatus)
    }

    private fun updateMessagesStatus(
        friendEmail: String,
        messageIds: List<String>,
        targetStatus: MessageStatus,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        val db = resolveFirestore() ?: return
        val myEmail = auth.currentUser?.email?.trim()?.lowercase().orEmpty()
        val peerEmail = friendEmail.trim().lowercase()
        val cleanIds = messageIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanIds.isEmpty() || !isValidEmail(myEmail) || !isValidEmail(peerEmail)) return

        val roomId = buildChatRoomId(myEmail, peerEmail)
        val messagesRef = db.collection("chats").document(roomId).collection("messages")
        val updatePayload = mapOf(
            "status" to targetStatus.name,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        cleanIds.chunked(400).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { messageId ->
                batch.update(messagesRef.document(messageId), updatePayload)
            }
            batch.commit().addOnFailureListener { error ->
                handleFirestoreError(error, OperationType.UPDATE, "chats/$roomId/messages")
                onFailure?.invoke(error)
            }
        }
    }

    private fun normalizeClientMessageStatus(rawStatus: String): String {
        return when (rawStatus.trim().lowercase()) {
            ChatMessage.STATUS_READ -> ChatMessage.STATUS_READ
            ChatMessage.STATUS_DELIVERED -> ChatMessage.STATUS_DELIVERED
            else -> ChatMessage.STATUS_SENT
        }
    }

    // 3. Real-time call signaling (/calls/{roomId}) with Agora-safe channelName
    fun initiateCall(friendEmail: String, callType: CallType = CallType.AUDIO): String? {
        val db = resolveFirestore() ?: return null
        val currentUser = auth.currentUser ?: return null
        val callerEmail = currentUser.email?.trim()?.lowercase().orEmpty()
        val calleeEmail = friendEmail.trim().lowercase()
        if (!isValidEmail(callerEmail) || !isValidEmail(calleeEmail)) return null

        val roomId = buildChatRoomId(callerEmail, calleeEmail)
        val channelName = CallSignaling.buildCallChannelName(roomId, callerEmail, calleeEmail)
        val participantEmails = listOf(callerEmail, calleeEmail).sorted()
        val callerName = currentUser.displayName
            ?.trim()
            ?.take(60)
            ?.ifBlank { null }
            ?: callerEmail.substringBefore("@").take(60).ifBlank { "Just Chat User" }

        val docPath = "calls/$roomId"
        val docRef = db.collection("calls").document(roomId)

        docRef.get()
            .addOnSuccessListener { existing ->
                if (!existing.exists()) {
                    val createPayload = mapOf(
                        "chatRoomId" to roomId,
                        "channelName" to channelName,
                        "callerUid" to currentUser.uid,
                        "caller" to callerEmail,
                        "callerName" to callerName,
                        "callee" to calleeEmail,
                        "participantEmails" to participantEmails,
                        "callType" to callType.name,
                        "status" to CallInvite.STATUS_CALLING,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    docRef.set(createPayload).addOnFailureListener { err ->
                        handleFirestoreError(err, OperationType.CREATE, docPath)
                    }
                } else {
                    val restartPayload = mapOf(
                        "channelName" to channelName,
                        "callerUid" to currentUser.uid,
                        "caller" to callerEmail,
                        "callerName" to callerName,
                        "callee" to calleeEmail,
                        "callType" to callType.name,
                        "status" to CallInvite.STATUS_CALLING,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (existing.getString("status") != CallInvite.STATUS_ENDED) {
                        docRef.update(
                            mapOf(
                                "status" to CallInvite.STATUS_ENDED,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).addOnSuccessListener {
                            docRef.update(restartPayload).addOnFailureListener { err ->
                                handleFirestoreError(err, OperationType.UPDATE, docPath)
                            }
                        }.addOnFailureListener { err ->
                            handleFirestoreError(err, OperationType.UPDATE, docPath)
                        }
                    } else {
                        docRef.update(restartPayload).addOnFailureListener { err ->
                            handleFirestoreError(err, OperationType.UPDATE, docPath)
                        }
                    }
                }
            }
            .addOnFailureListener { err ->
                handleFirestoreError(err, OperationType.GET, docPath)
            }

        return channelName
    }

    fun acceptCall(friendEmail: String) {
        val db = resolveFirestore() ?: return
        val myEmail = auth.currentUser?.email?.trim()?.lowercase().orEmpty()
        val peerEmail = friendEmail.trim().lowercase()
        if (!isValidEmail(myEmail) || !isValidEmail(peerEmail)) return

        val roomId = buildChatRoomId(myEmail, peerEmail)
        val docPath = "calls/$roomId"
        db.collection("calls")
            .document(roomId)
            .update(
                mapOf(
                    "status" to CallInvite.STATUS_ACCEPTED,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .addOnFailureListener { err ->
                handleFirestoreError(err, OperationType.UPDATE, docPath)
            }
    }

    fun observeCall(
        friendEmail: String,
        onSignal: (CallSignal?) -> Unit
    ): ListenerRegistration? {
        val db = resolveFirestore() ?: return null
        val myEmail = auth.currentUser?.email?.trim()?.lowercase().orEmpty()
        val peerEmail = friendEmail.trim().lowercase()
        if (!isValidEmail(myEmail) || !isValidEmail(peerEmail)) return null

        val roomId = buildChatRoomId(myEmail, peerEmail)
        val path = "calls/$roomId"

        return db.collection("calls")
            .document(roomId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    handleFirestoreError(error, OperationType.GET, path)
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) {
                    onSignal(null)
                    return@addSnapshotListener
                }
                val invite = snapshot.toObject(
                    CallInvite::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                onSignal(
                    invite?.let {
                        CallSignal(
                            channelName = it.channelName,
                            caller = it.caller,
                            callee = it.callee,
                            status = it.status,
                            updatedAt = it.updatedAtMillis
                        )
                    }
                )
            }
    }

    fun endCall(friendEmail: String) {
        val db = resolveFirestore() ?: return
        val myEmail = auth.currentUser?.email?.trim()?.lowercase().orEmpty()
        val peerEmail = friendEmail.trim().lowercase()
        if (!isValidEmail(myEmail) || !isValidEmail(peerEmail)) return

        val roomId = buildChatRoomId(myEmail, peerEmail)
        val docPath = "calls/$roomId"
        val docRef = db.collection("calls").document(roomId)
        docRef.get()
            .addOnSuccessListener { existing ->
                if (existing.exists() && existing.getString("status") != CallInvite.STATUS_ENDED) {
                    docRef.update(
                        mapOf(
                            "status" to CallInvite.STATUS_ENDED,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).addOnFailureListener { err ->
                        handleFirestoreError(err, OperationType.UPDATE, docPath)
                    }
                }
            }
            .addOnFailureListener { err ->
                handleFirestoreError(err, OperationType.GET, docPath)
            }
    }

    private fun isValidEmail(email: String): Boolean {
        return email.length in 3..120 && EMAIL_REGEX.matches(email)
    }
}
