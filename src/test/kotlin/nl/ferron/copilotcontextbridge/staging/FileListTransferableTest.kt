package nl.ferron.copilotcontextbridge.staging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.datatransfer.DataFlavor
import java.io.File

class FileListTransferableTest {
    @Test
    fun exposesFilesFirstAndReadablePathsAsText() {
        val files = listOf(File("00_REPO_CONTEXT.md"), File("src__service.py"))
        val transferable = FileListTransferable(files, "00_REPO_CONTEXT.md\nsrc/service.py")

        assertArrayEquals(
            arrayOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor),
            transferable.transferDataFlavors,
        )
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertFalse(transferable.isDataFlavorSupported(DataFlavor.imageFlavor))
        assertEquals(files, transferable.getTransferData(DataFlavor.javaFileListFlavor))
        assertEquals("00_REPO_CONTEXT.md\nsrc/service.py", transferable.getTransferData(DataFlavor.stringFlavor))
    }
}
