package com.smsairelay.app

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class RelayServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChatDatabase

    private val originalRepository = SmsRelay.repositoryFor
    private val originalClient = SmsRelay.clientFor
    private val originalSend = SmsRelay.sendSms
    private val originalKey = SettingsStore.secretKey

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).build()
        val repository = ConversationRepository(db.messageDao())
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SettingsStore.secretKey = { key }
        SmsRelay.repositoryFor = { repository }
    }

    @After
    fun tearDown() {
        SmsRelay.repositoryFor = originalRepository
        SmsRelay.clientFor = originalClient
        SmsRelay.sendSms = originalSend
        SettingsStore.secretKey = originalKey
        db.close()
    }

    private fun startService(intent: Intent): Pair<RelayService, Int> {
        val service = Robolectric.buildService(RelayService::class.java, intent).create().get()
        return service to service.onStartCommand(intent, 0, 1)
    }

    @Test
    fun goesForegroundWithOngoingNotification() {
        val (service, _) = startService(Intent(context, RelayService::class.java))

        val shadow = shadowOf(service)
        assertFalse(shadow.isForegroundStopped)
        assertEquals(1, shadow.lastForegroundNotificationId)
        val notification = shadow.lastForegroundNotification
        assertNotNull(notification)
        assertEquals("relay_status", notification.channelId)
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun createsLowImportanceChannel() {
        startService(Intent(context, RelayService::class.java))
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel("relay_status")
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun asksToBeRestartedIfKilled() {
        val (_, result) = startService(Intent(context, RelayService::class.java))
        assertEquals(Service.START_STICKY, result)
    }

    @Test
    fun nullIntentFromStickyRestartIsHandled() {
        val service = Robolectric.buildService(RelayService::class.java).create().get()
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertFalse(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun runsIncomingMessageThroughTheRelay() {
        val sent = CountDownLatch(1)
        var reply: Pair<String, String>? = null
        SmsRelay.clientFor = { FakeAiClient().apply { nextResult = { Result.success("pong") } } }
        SmsRelay.sendSms = { _, to, text -> reply = to to text; sent.countDown() }
        SettingsStore.setApiKey(context, AiProvider.CLAUDE, "sk-ant-test")

        val intent = Intent(context, RelayService::class.java)
            .putExtra("sender", "+919876543210")
            .putExtra("body", "ping")
        startService(intent)

        assertTrue("reply was not sent", sent.await(5, TimeUnit.SECONDS))
        assertEquals("+919876543210" to "pong", reply)
    }

    @Test
    fun startWithoutMessageSendsNothing() {
        var sends = 0
        SmsRelay.sendSms = { _, _, _ -> sends++ }
        startService(Intent(context, RelayService::class.java))
        Thread.sleep(200)
        assertEquals(0, sends)
    }

    // --- Companion helpers used by the receivers and MainActivity ---

    @Test
    fun enqueuePutsMessageInTheStartIntent() {
        assertTrue(RelayService.enqueue(context, "+15550100", "hello"))
        val started = shadowOf(context as android.app.Application).nextStartedService
        assertEquals(RelayService::class.java.name, started.component!!.className)
        assertEquals("+15550100", started.getStringExtra("sender"))
        assertEquals("hello", started.getStringExtra("body"))
    }

    @Test
    fun startReportsSuccess() {
        assertTrue(RelayService.start(context))
    }
}
