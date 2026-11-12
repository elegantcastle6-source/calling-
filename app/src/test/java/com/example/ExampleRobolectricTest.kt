package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CallManager
import com.example.data.ChatRepository
import com.example.model.CallType
import com.example.model.MediaType
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
        assertEquals("GlideChat", appName)
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
}
