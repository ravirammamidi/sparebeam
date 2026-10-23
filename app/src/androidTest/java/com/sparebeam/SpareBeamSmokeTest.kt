package com.sparebeam

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sparebeam.storage.ImportedMediaStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        composeRule.onNodeWithText("+ Create folder").performClick()
        composeRule.onNodeWithText("Folder name").performTextInput("CI smoke folder")
        composeRule.onNodeWithText("Create").performClick()
        composeRule.onNodeWithText("CI smoke folder").assertIsDisplayed()
        composeRule.onNodeWithText("0 copied item(s) · stored in SpareBeam").assertIsDisplayed()
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
