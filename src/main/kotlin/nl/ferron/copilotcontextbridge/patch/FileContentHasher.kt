package nl.ferron.copilotcontextbridge.patch

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.jetbrains.python.psi.PyFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** SHA-256 over the exact current file text, without line-ending or whitespace normalization. */
object FileContentHasher {
    fun hash(text: String): String =
        "sha256:" +
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

    /**
     * Hashes the same byte representation used by staging: saved files use their exact on-disk bytes,
     * while an unsaved document uses its current text encoded with the file's configured charset.
     */
    fun hash(file: PyFile): String {
        val virtualFile = file.virtualFile
        val document = FileDocumentManager.getInstance().getCachedDocument(virtualFile)
        val bytes =
            if (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
                document.text.toByteArray(virtualFile.charset)
            } else {
                virtualFile.contentsToByteArray()
            }
        return hash(bytes)
    }

    private fun hash(bytes: ByteArray): String =
        "sha256:" +
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
}
