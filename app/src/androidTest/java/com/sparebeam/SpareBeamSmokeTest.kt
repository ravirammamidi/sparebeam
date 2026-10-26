package com.sparebeam

import android.net.Uri
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sparebeam.storage.ImportedMediaStore
import com.sparebeam.server.MediaServerService
import java.net.Socket
import java.net.URI
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpareBeamSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun userCanCreatePrivateFolderFromHomeScreen() {
        composeRule.onNodeWithText("SpareBeam").assertIsDisplayed()
        composeRule.onNodeWithText("Create folder").performClick()
        composeRule.onNodeWithText("Folder name").performTextInput("CI smoke folder")
        composeRule.onNodeWithText("Create").performClick()
        composeRule.onNodeWithText("CI smoke folder").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("0 items · On this phone").assertIsDisplayed()
    }

    @Test
    fun startsServerOnWifiAndServesHomePage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val address = MediaServerService.address(context)
        assertNotNull("The Wi-Fi address must be readable on the test device", address)
        try {
            context.startForegroundService(Intent(context, MediaServerService::class.java).setAction(MediaServerService.ACTION_START))
            composeRule.waitUntil(10_000) {
                MediaServerService.running.value || MediaServerService.errorMessage.value != null
            }
            assertTrue(MediaServerService.errorMessage.value, MediaServerService.running.value)
            composeRule.onNodeWithText("Stop server").assertIsDisplayed()
            val uri = URI(address!!)
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(uri.host, uri.port), 5_000)
                socket.soTimeout = 5_000
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nConnection: close\r\n\r\n".toByteArray())
                val status = socket.getInputStream().bufferedReader().readLine()
                assertTrue("Unexpected response: $status", status.startsWith("HTTP/1.1 200"))
            }
        } finally {
            context.startService(Intent(context, MediaServerService::class.java).setAction(MediaServerService.ACTION_STOP))
            composeRule.waitUntil(5_000) { !MediaServerService.running.value }
        }
    }

    @Test
    fun copiesOnlyIntoPrivateFolderAndRejectsEscapingPaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = "import-test-${System.currentTimeMillis()}"
        assertEquals(folder, ImportedMediaStore.createFolder(context, folder))
        val source = File(context.cacheDir, "smoke-${System.currentTimeMillis()}.jpg")
        source.writeBytes(byteArrayOf(1, 2, 3, 4))

        val result = ImportedMediaStore.copyInto(context, folder, listOf(Uri.fromFile(source)))
        assertEquals(1, result.copied)
        val copied = ImportedMediaStore.fileForRequest(context, "$folder/${source.name}")
        assertNotNull(copied)
        assertEquals(source.readBytes().toList(), copied!!.readBytes().toList())
        assertNull(ImportedMediaStore.fileForRequest(context, "$folder/../../shared/secret.jpg"))
    }
}
