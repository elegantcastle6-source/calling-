package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AgoraCallController
import com.example.data.AgoraTokenProvider
import com.example.data.CallInvite
import com.example.data.CallManager
import com.example.data.CallSignaling
import com.example.data.ChatRepository
import com.example.data.SharedChatMessageDoc
import com.example.model.CallType
import com.example.model.MediaType
import com.example.model.MessageStatus
import com.example.utils.buildChatRoomId
import com.google.firebase.Timestamp
import com.justchat.app.sync.CallSignal
import com.justchat.app.sync.JustChatManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Just Chat", appName)
    }

    @Test
    fun `add contact by gmail and send message`() {
        val repository = ChatRepository()
        val result = repository.addContactByGmail("john.doe@gmail.com", "John Doe")
        assertTrue(result.isSuccess)
        val contact = result.getOrNull()
        assertNotNull(contact)
        assertEquals("john.doe@gmail.com", contact?.gmail)
        assertTrue(contact!!.securityFingerprint.isNotBlank())

        repository.sendMessage(
            contactId = contact.id,
            text = "Testing zero lag message",
            mediaType = MediaType.NONE
        )

        val messages = repository.messages.value[contact.id]
        assertNotNull(messages)
        assertTrue(messages!!.any { it.text == "Testing zero lag message" && it.isEncrypted })
    }

    @Test
    fun `record completed low data call updates data saved`() {
        val repository = ChatRepository()
        val initialSaved = repository.totalDataSavedMb.value
        val contact = repository.contacts.value.first()

        repository.recordCompletedCall(
            contact = contact,
            callType = CallType.VIDEO,
            durationSeconds = 120,
            wasLowDataMode = true,
            dataUsedKb = 5000L
        )

        val afterSaved = repository.totalDataSavedMb.value
        assertTrue(afterSaved > initialSaved)
        val logs = repository.callLogs.value
        assertTrue(logs.any { it.contact.id == contact.id && it.durationSeconds == 120 && it.wasEncrypted })
    }

    @Test
    fun `in-call media sharing updates active call state`() {
        val testScope = TestScope()
        val repository = ChatRepository()
        val callManager = CallManager(repository, testScope)
        val contact = repository.contacts.value.first()

        callManager.startCall(contact, CallType.VIDEO, lowDataMode = true)
        val active = callManager.activeCall.value
        assertNotNull(active)
        assertTrue(active!!.isEndToEndEncrypted)

        callManager.shareMediaInCall("content://media/photo_123", MediaType.IMAGE, "Family Photo")
        assertEquals("content://media/photo_123", callManager.activeCall.value?.sharedMediaUri)
        assertEquals(MediaType.IMAGE, callManager.activeCall.value?.sharedMediaType)

        callManager.stopSharingMedia()
        assertEquals(null, callManager.activeCall.value?.sharedMediaUri)
        assertEquals(MediaType.NONE, callManager.activeCall.value?.sharedMediaType)

        callManager.endCall()
        assertEquals(null, callManager.activeCall.value)
    }

    @Test
    fun `smooth calling noise suppression and minimizing`() {
        val testScope = TestScope()
        val repository = ChatRepository()
        val callManager = CallManager(repository, testScope)
        val contact = repository.contacts.value.first()

        callManager.startCall(contact, CallType.AUDIO, lowDataMode = true)
        assertTrue(callManager.activeCall.value?.isNoiseSuppressionEnabled == true)

        callManager.toggleNoiseSuppression()
        assertFalse(callManager.activeCall.value?.isNoiseSuppressionEnabled == true)

        callManager.setMinimized(true)
        assertTrue(callManager.activeCall.value?.isMinimized == true)

        callManager.optimizeNetworkRoute()
        assertEquals(9, callManager.activeCall.value?.latencyMs)
        assertEquals(0.0f, callManager.activeCall.value?.packetLossPercent)

        callManager.endCall()
    }

    @Test
    fun `agora channel name is deterministic and symmetric for two gmails`() {
        val ch1 = AgoraCallController.buildChannelName("Alice@Gmail.com ", "bob@gmail.com")
        val ch2 = AgoraCallController.buildChannelName("BOB@gmail.com", "alice@gmail.com")
        assertEquals(ch1, ch2)
        assertTrue(ch1.startsWith("jc_"))
        assertTrue(ch1.length <= 64)
        assertTrue(ch1.all { it.isLetterOrDigit() || it == '_' })
    }

    @Test
    fun `agora app id validation rejects placeholders and accepts real hex id`() {
        assertFalse(AgoraCallController.isValidAppId(""))
        assertFalse(AgoraCallController.isValidAppId("YOUR_AGORA_APP_ID"))
        assertFalse(AgoraCallController.isValidAppId("<Your app ID>"))
        assertFalse(AgoraCallController.isValidAppId("YAHAN_APNI_AGORA_APP_ID_PASTE_KAREIN"))
        assertTrue(AgoraCallController.isValidAppId("9f86d081884c7d659a2feaa0c55ad015"))
    }

    @Test
    fun `agora token and channel placeholders fall back safely`() {
        assertEquals(null, AgoraCallController.normalizeToken("<Your token>"))
        assertEquals(null, AgoraCallController.normalizeToken("OPTIONAL_AGORA_TEMP_TOKEN"))
        assertEquals("007eJx_real_token", AgoraCallController.normalizeToken("007eJx_real_token"))
        assertEquals(
            "jc_fallback",
            AgoraCallController.resolveChannelName("<Your channel name>", "jc_fallback")
        )
        assertEquals(
            "jc_fallback",
            AgoraCallController.resolveChannelName("OPTIONAL_AGORA_CHANNEL_NAME", "jc_fallback")
        )
        assertEquals(
            "justchat_demo_room",
            AgoraCallController.resolveChannelName("justchat_demo_room", "jc_fallback")
        )
    }

    @Test
    fun `agora token provider validates server urls and parses json or raw responses`() {
        assertFalse(AgoraTokenProvider.isValidServerUrl(""))
        assertFalse(AgoraTokenProvider.isValidServerUrl("OPTIONAL_AGORA_TOKEN_SERVER_URL"))
        assertFalse(AgoraTokenProvider.isValidServerUrl("not-a-url"))
        assertTrue(AgoraTokenProvider.isValidServerUrl("https://tokens.justchat.app/rtc-token"))

        assertEquals(
            "007eJx_dynamic_token",
            AgoraTokenProvider.parseTokenFromBody("""{"rtcToken":"007eJx_dynamic_token","expiresIn":3600}""")
        )
        assertEquals(
            "007eJx_alt_token",
            AgoraTokenProvider.parseTokenFromBody("""{"token":"007eJx_alt_token"}""")
        )
        assertEquals(
            "007eJx_plain_token",
            AgoraTokenProvider.parseTokenFromBody("007eJx_plain_token")
        )
        assertEquals(null, AgoraTokenProvider.parseTokenFromBody(""))
        assertEquals(null, AgoraTokenProvider.parseTokenFromBody("<html>error</html>"))
    }

    @Test
    fun `buildChatRoomId is deterministic symmetric and firebase key safe`() {
        val room1 = buildChatRoomId("  Bob@Gmail.com ", "alice@gmail.com")
        val room2 = buildChatRoomId("ALICE@gmail.com", "bob@gmail.com")
        assertEquals(room1, room2)
        assertEquals("alice_gmail_com_bob_gmail_com", room1)
        assertTrue(room1.matches(Regex("^[a-zA-Z0-9_]+$")))
    }

    @Test
    fun `shared chat message maps sender alignment correctly for both participants`() {
        val doc = SharedChatMessageDoc(
            id = "msg_101",
            chatRoomId = buildChatRoomId("alice@gmail.com", "bob@gmail.com"),
            senderUid = "alice_uid",
            senderEmail = "alice@gmail.com",
            receiverEmail = "bob@gmail.com",
            participantEmails = listOf("alice@gmail.com", "bob@gmail.com"),
            text = "Hello Bob!",
            status = MessageStatus.DELIVERED.name,
            reactions = mapOf("👍" to 1L),
            encrypted = true,
            createdAt = Timestamp(1_700_000_000L, 0),
            updatedAt = Timestamp(1_700_000_000L, 0)
        )

        val aliceView = doc.toDomainMessage("alice_uid", "Alice@Gmail.com")
        assertEquals("current_user", aliceView.senderId)
        assertEquals("bob@gmail.com", aliceView.receiverId)
        assertEquals(MessageStatus.DELIVERED, aliceView.status)
        assertEquals(mapOf("👍" to 1), aliceView.reactions)

        val bobView = doc.toDomainMessage("bob_uid", "bob@gmail.com")
        assertEquals("alice@gmail.com", bobView.senderId)
        assertEquals("bob@gmail.com", bobView.receiverId)
        assertEquals("Hello Bob!", bobView.text)
    }

    @Test
    fun `call signaling channel name uses call_chatRoomId and stays within Agora 64-byte limit`() {
        val shortRoom = buildChatRoomId("alice@gmail.com", "bob@gmail.com")
        val shortChannel = CallSignaling.buildCallChannelName(shortRoom, "alice@gmail.com", "bob@gmail.com")
        assertEquals("call_alice_gmail_com_bob_gmail_com", shortChannel)
        assertTrue(shortChannel.length <= 64)

        val longA = "very.long.username.number.one.participant@gmail.com"
        val longB = "very.long.username.number.two.participant@gmail.com"
        val longRoom = buildChatRoomId(longA, longB)
        val longChannel = CallSignaling.buildCallChannelName(longRoom, longA, longB)
        assertTrue(longChannel.length <= 64)
        assertEquals(
            CallSignaling.buildCallChannelName(longRoom, longB, longA),
            longChannel
        )

        val invite = CallInvite(
            chatRoomId = shortRoom,
            channelName = shortChannel,
            caller = "alice@gmail.com",
            callee = "bob@gmail.com",
            callType = CallType.VIDEO.name,
            status = CallInvite.STATUS_CALLING
        )
        assertEquals(CallType.VIDEO, invite.parsedCallType())
        assertEquals("calling", invite.status)
    }

    @Test
    fun `justChatManager builds symmetric room id and default call signal`() {
        val room1 = JustChatManager.buildChatRoomId("Alice@Gmail.com", "bob@gmail.com")
        val room2 = JustChatManager.buildChatRoomId("  BOB@gmail.com ", "alice@gmail.com")
        assertEquals(room1, room2)
        assertEquals("alice_gmail_com_bob_gmail_com", room1)

        val signal = CallSignal(
            channelName = "call_$room1",
            caller = "alice@gmail.com",
            callee = "bob@gmail.com"
        )
        assertEquals("calling", signal.status)
        assertEquals("call_alice_gmail_com_bob_gmail_com", signal.channelName)
    }
}
