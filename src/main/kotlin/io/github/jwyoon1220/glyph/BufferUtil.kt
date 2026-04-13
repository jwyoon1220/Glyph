package io.github.jwyoon1220.glyph

/**
 * Hard limits for the in-memory text editing buffer used by [PieceTable].
 *
 * The add-buffer stores every character ever typed or pasted in the current
 * session (deleted text is logically removed via the piece list but the raw
 * bytes remain in the buffer until [PieceTable.setText] is called).  Capping
 * the buffer prevents unbounded memory growth in long editing sessions.
 *
 * **Limit: 64 MiB**
 *
 * A Java `Char` occupies 2 bytes (UTF-16), so the buffer can hold up to
 * [MAX_CHARS] characters — roughly 32 million characters, far more than any
 * typical novel or technical document.
 */
object BufferUtil {

    /** Maximum byte size of the editing add-buffer: 64 MiB. */
    const val MAX_BUFFER_BYTES: Long = 64L * 1024L * 1024L        // 67,108,864 bytes

    /**
     * Maximum number of characters that may accumulate in the add-buffer.
     * Each Java char is 2 bytes, so this equals [MAX_BUFFER_BYTES] / 2.
     */
    const val MAX_CHARS: Int = (MAX_BUFFER_BYTES / 2L).toInt()    // 33,554,432 chars

    /**
     * Asserts that [requiredChars] does not exceed [MAX_CHARS].
     *
     * Call this *before* growing the add-buffer in [PieceTable] so that the
     * allocation never happens when the limit is already reached.
     *
     * @param requiredChars Total chars the add-buffer needs to hold after the
     *                      pending write.
     * @throws TextBufferFullException when [requiredChars] > [MAX_CHARS].
     */
    fun requireCapacity(requiredChars: Int) {
        if (requiredChars > MAX_CHARS) {
            val limitMiB = MAX_BUFFER_BYTES / (1024L * 1024L)
            throw TextBufferFullException(
                "Text buffer limit reached ($limitMiB MiB / $MAX_CHARS chars). " +
                    "Save the document and re-open it to continue editing."
            )
        }
    }
}

/**
 * Thrown by [BufferUtil.requireCapacity] when a write to the [PieceTable]
 * add-buffer would exceed [BufferUtil.MAX_BUFFER_BYTES].
 */
class TextBufferFullException(message: String) : RuntimeException(message)
