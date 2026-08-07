package nl.ferron.copilotcontextbridge.staging

import junit.framework.TestCase
import java.nio.file.Files

class TextFileSupportTest : TestCase() {
    fun testDetectsUnknownTextAndRejectsBinary() {
        val directory = Files.createTempDirectory("ccb-text-detection")
        try {
            val robot = directory.resolve("suite.robot")
            val binary = directory.resolve("image.bin")
            Files.writeString(robot, "*** Test Cases ***\nExample\n    Log    safe\n")
            Files.write(binary, byteArrayOf(0, 1, 2, 3, 4))

            assertTrue(TextFileSupport.isLikelyText(robot))
            assertTrue(TextFileSupport.requiresMicrosoft365TextCopy("suite.robot"))
            assertFalse(TextFileSupport.isLikelyText(binary))
            assertFalse(TextFileSupport.requiresMicrosoft365TextCopy("source.py"))
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
