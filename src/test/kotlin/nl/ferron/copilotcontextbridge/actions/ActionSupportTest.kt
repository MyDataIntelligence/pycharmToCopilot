package nl.ferron.copilotcontextbridge.actions

import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionSupportTest {
    @Test
    fun keepsEveryFileFromSingleAndMultiSelectionDataKeys() {
        val first = LightVirtualFile("first.py")
        val second = LightVirtualFile("second.py")

        val result =
            ActionSupport.mergeFiles(
                listOf(first, second),
                listOf(first),
            )

        assertEquals(listOf(first, second), result)
    }

    @Test
    fun acceptsVirtualFilesFromSelectedItemsFallback() {
        val selected = LightVirtualFile("selected.py")

        assertEquals(selected, ActionSupport.virtualFileOf(selected))
    }
}
