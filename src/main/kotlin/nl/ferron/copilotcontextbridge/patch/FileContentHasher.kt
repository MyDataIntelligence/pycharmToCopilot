package nl.ferron.copilotcontextbridge.patch

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
}
