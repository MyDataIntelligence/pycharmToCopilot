package nl.ferron.copilotcontextbridge.actions

import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun projectViewActionsAreGroupedInAVisibleBridgeSubmenu() {
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()

        assertTrue(
            pluginXml.contains(
                "<group id=\"CopilotContextBridge.ProjectViewGroup\" text=\"Copilot Context Bridge\" popup=\"true\">",
            ),
        )
    }
}
