package nl.ferron.copilotcontextbridge.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Central-directory check for Unix symlinks/devices, which ZipInputStream otherwise exposes as ordinary bytes. */
object ZipMetadataSafety {
    fun specialEntryNames(bytes: ByteArray): Set<String> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val result = mutableSetOf<String>()
        var offset = 0
        while (offset + 46 <= bytes.size) {
            if (buffer.getInt(offset) != CENTRAL_SIGNATURE) {
                offset++
                continue
            }
            val madeBy = buffer.getShort(offset + 4).toInt() and 0xffff
            val flags = buffer.getShort(offset + 8).toInt() and 0xffff
            val nameLength = buffer.getShort(offset + 28).toInt() and 0xffff
            val extraLength = buffer.getShort(offset + 30).toInt() and 0xffff
            val commentLength = buffer.getShort(offset + 32).toInt() and 0xffff
            val end = offset + 46 + nameLength + extraLength + commentLength
            if (end > bytes.size) {
                break
            }
            if ((madeBy ushr 8) == UNIX) {
                val fileType = ((buffer.getInt(offset + 38) ushr 16) and 0xffff) and FILE_TYPE_MASK
                if (fileType != 0 && fileType != REGULAR && fileType != DIRECTORY) {
                    val charset = if ((flags and UTF8_FLAG) != 0) StandardCharsets.UTF_8 else Charsets.ISO_8859_1
                    result += String(bytes, offset + 46, nameLength, charset)
                }
            }
            offset = end
        }
        return result
    }

    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val UNIX = 3
    private const val UTF8_FLAG = 0x800
    private const val FILE_TYPE_MASK = 0xF000
    private const val REGULAR = 0x8000
    private const val DIRECTORY = 0x4000
}
