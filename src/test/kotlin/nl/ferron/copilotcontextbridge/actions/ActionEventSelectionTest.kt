package nl.ferron.copilotcontextbridge.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ActionEventSelectionTest : BasePlatformTestCase() {
    fun testReadsCompleteMultiSelectionFromRealActionEventDataContext() {
        val first = LightVirtualFile("first.py")
        val second = LightVirtualFile("second.py")
        val context =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(first, second))
                .build()

        @Suppress("DEPRECATION")
        val event = AnActionEvent.createFromDataContext("test", null, context)

        assertEquals(listOf(first, second), ActionSupport.files(event))
    }
}
