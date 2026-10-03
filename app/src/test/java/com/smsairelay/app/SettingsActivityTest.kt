package com.smsairelay.app

import android.content.Context
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalKey = SettingsStore.secretKey

    @Before
    fun setUp() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SettingsStore.secretKey = { key }
    }

    @After
    fun tearDown() {
        SettingsStore.secretKey = originalKey
    }

    @Test
    fun systemPromptFieldIsEmptyWithDefaultAsHint() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val input = activity.findViewById<EditText>(R.id.systemPromptInput)
        assertEquals("", input.text.toString())
        assertEquals(SMS_SYSTEM_PROMPT, input.hint.toString())
    }

    @Test
    fun savingStoresCustomPromptAndClearingRestoresDefault() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val input = activity.findViewById<EditText>(R.id.systemPromptInput)
        val save = activity.findViewById<Button>(R.id.saveButton)

        input.setText("Answer like a pirate.")
        save.performClick()
        assertEquals("Answer like a pirate.", SettingsStore.getSystemPrompt(context))

        val reopened = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val reopenedInput = reopened.findViewById<EditText>(R.id.systemPromptInput)
        assertEquals("Answer like a pirate.", reopenedInput.text.toString())

        reopenedInput.setText("")
        reopened.findViewById<Button>(R.id.saveButton).performClick()
        assertNull(SettingsStore.getCustomSystemPrompt(context))
    }
}
