package nl.ferron.copilotcontextbridge.analysis

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object FunctionHasher {
    fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalize(text).toByteArray(StandardCharsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}
