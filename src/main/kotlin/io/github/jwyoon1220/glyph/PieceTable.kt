package io.github.jwyoon1220.glyph

import it.unimi.dsi.fastutil.objects.ObjectArrayList

/**
 * Optimized PieceTable for storing and editing text.
 *
 * Performance improvements over the naïve implementation:
 *  - Prefix-sum offset cache (lazily built, O(n) to build, O(log n) to look up a
 *    piece by character offset via binary search — covers getText, insert, delete).
 *  - Adjacent-piece coalescing: when inserting text that immediately extends the
 *    last piece in the ADD buffer we just grow that piece rather than adding a new
 *    one, keeping the piece list short and the binary search fast.
 *  - CharArray add-buffer: direct char[] access avoids StringBuilder range-check
 *    overhead on hot getText paths.
 *  - Early-exit in getText: stops iterating once the requested range is satisfied.
 */
class PieceTable(initialText: String = "") {
    private val originalBuffer: CharArray = initialText.toCharArray()
    // Grow-doubling add buffer — individual elements are written once and never
    // changed; the array reference is replaced (grown) in appendToAddBuffer and
    // reset to a fresh allocation in setText.
    private var addBuffer: CharArray = CharArray(maxOf(64, initialText.length * 2))
    private var addLength = 0

    enum class BufferType { ORIGINAL, ADD }

    data class Piece(val source: BufferType, val offset: Int, var length: Int)

    private val pieces = ObjectArrayList<Piece>(16)
    private var totalLength = initialText.length

    // --- Offset cache (prefix sums) ---
    // offsetCache[i] = character offset of the *start* of pieces[i].
    // null means dirty and must be rebuilt before use.
    private var offsetCache: IntArray? = null

    init {
        if (initialText.isNotEmpty()) {
            pieces.add(Piece(BufferType.ORIGINAL, 0, initialText.length))
        }
    }

    // ------------------------------------------------------------------ cache

    private fun invalidateCache() { offsetCache = null }

    /** Returns (and caches) the prefix-sum offset array. O(n) on first call after
     *  any mutation, O(1) on subsequent reads until the next mutation. */
    private fun cache(): IntArray {
        offsetCache?.let { return it }
        val c = IntArray(pieces.size + 1) // c[i] = start of pieces[i]; c[size] = totalLength
        var pos = 0
        for (i in pieces.indices) {
            c[i] = pos
            pos += pieces[i].length
        }
        c[pieces.size] = pos
        return c.also { offsetCache = it }
    }

    /** Binary-search: returns the index of the piece that *contains* [offset].
     *  Returns pieces.size if offset == totalLength (i.e. appending at end). */
    private fun pieceIndexAt(offset: Int): Int {
        val c = cache()
        var lo = 0; var hi = pieces.size - 1
        while (lo <= hi) {
            val mid = (lo + hi).ushr(1)
            when {
                c[mid + 1] <= offset -> lo = mid + 1
                c[mid]     >  offset -> hi = mid - 1
                else -> return mid
            }
        }
        return pieces.size // at the very end
    }

    // -------------------------------------------------------------- add buffer

    private fun appendToAddBuffer(text: String): Int {
        val needed = addLength + text.length
        BufferUtil.requireCapacity(needed)
        if (needed > addBuffer.size) {
            addBuffer = addBuffer.copyOf(maxOf(needed, addBuffer.size * 2))
        }
        val start = addLength
        text.toCharArray(addBuffer, start)
        addLength += text.length
        return start
    }

    // ----------------------------------------------------------------- insert

    fun insert(index: Int, text: String) {
        if (index !in 0..totalLength) throw IndexOutOfBoundsException("index=$index, length=$totalLength")
        if (text.isEmpty()) return

        val addOffset = appendToAddBuffer(text)
        totalLength += text.length

        // Fast path: coalesce with the last piece when appending at document end.
        // At this point totalLength already includes text.length, so
        // (totalLength - text.length) == the pre-insert total == the required insert index.
        if (index == totalLength - text.length && pieces.isNotEmpty()) {
            val last = pieces.last()
            if (last.source == BufferType.ADD && last.offset + last.length == addOffset) {
                last.length += text.length
                invalidateCache()
                return
            }
        }

        val newPiece = Piece(BufferType.ADD, addOffset, text.length)

        if (index == 0) {
            pieces.add(0, newPiece)
            invalidateCache()
            return
        }

        val c = cache()
        val i = pieceIndexAt(index)

        if (i == pieces.size) {
            // After all pieces — append
            pieces.add(newPiece)
            invalidateCache()
            return
        }

        val p = pieces[i]
        val splitIndex = index - c[i]

        if (splitIndex == 0) {
            // Insert before piece i
            pieces.add(i, newPiece)
        } else {
            // Split piece i at splitIndex
            val rightPiece = Piece(p.source, p.offset + splitIndex, p.length - splitIndex)
            p.length = splitIndex
            pieces.add(i + 1, newPiece)
            pieces.add(i + 2, rightPiece)
        }
        invalidateCache()
    }

    // ----------------------------------------------------------------- delete

    fun delete(index: Int, length: Int) {
        if (index < 0 || length < 0 || index + length > totalLength) throw IndexOutOfBoundsException()
        if (length == 0) return

        totalLength -= length
        val deletionEnd = index + length

        // Capture piece start offsets *before* mutating so we can iterate correctly.
        val c = cache()
        // Find the first piece that overlaps the deletion range.
        var i = pieceIndexAt(index)
        // pStart tracks the character-offset of pieces[i] as we iterate.
        // We derive it from the (pre-mutation) cache.
        var pStart = if (i < pieces.size) c[i] else totalLength + length

        invalidateCache()

        while (i < pieces.size && pStart < deletionEnd) {
            val p = pieces[i]
            val pEnd = pStart + p.length

            if (pEnd <= index) { pStart = pEnd; i++; continue } // before deletion range

            val overlapStart = (index - pStart).coerceAtLeast(0)
            val overlapEnd   = (deletionEnd - pStart).coerceAtMost(p.length)
            val deleteCount  = overlapEnd - overlapStart
            if (deleteCount <= 0) { pStart = pEnd; i++; continue }

            when {
                overlapStart == 0 && overlapEnd == p.length -> {
                    // Entire piece consumed
                    pieces.removeAt(i)
                    pStart = pEnd // pStart for next piece (which is now at index i)
                    // Don't advance i — the piece at i is now what was i+1
                }
                overlapStart == 0 -> {
                    pieces[i] = Piece(p.source, p.offset + overlapEnd, p.length - overlapEnd)
                    pStart = pEnd; i++
                }
                overlapEnd == p.length -> {
                    p.length = overlapStart
                    pStart = pEnd; i++
                }
                else -> {
                    // Middle deletion — split
                    val right = Piece(p.source, p.offset + overlapEnd, p.length - overlapEnd)
                    p.length = overlapStart
                    pieces.add(i + 1, right)
                    pStart = pEnd; i += 2
                }
            }
        }
    }

    // ----------------------------------------------------------------- getText

    /**
     * Efficiently extracts text in [start, end).
     * Uses the offset cache to binary-search the start piece, then
     * iterates forward until [end] is reached.
     */
    fun getText(start: Int, end: Int): String {
        if (start >= end || start < 0 || end > totalLength) return ""

        val startPiece = pieceIndexAt(start) // also warms the cache
        val c = cache()
        val builder = StringBuilder(end - start)

        var i = startPiece
        var remaining = end - start
        var pieceOff = start - c[startPiece] // offset within the first piece

        while (i < pieces.size && remaining > 0) {
            val p = pieces[i]
            val available = p.length - pieceOff
            if (available <= 0) { pieceOff = 0; i++; continue }
            val buf = if (p.source == BufferType.ORIGINAL) originalBuffer else addBuffer
            // Clamp to actual buffer capacity — guards against any piece that
            // has drifted out of bounds due to concurrent resets or other
            // invariant violations, preventing an OOB crash on the EDT.
            val bufRemaining = buf.size - p.offset - pieceOff
            val take = minOf(available, remaining, bufRemaining)
            if (take <= 0) { pieceOff = 0; i++; continue }
            builder.append(buf, p.offset + pieceOff, p.offset + pieceOff + take)
            remaining -= take
            pieceOff = 0
            i++
        }

        return builder.toString()
    }

    // --------------------------------------------------------------- setText

    fun setText(newText: String) {
        pieces.clear()
        // Allocate a fresh add-buffer sized for the new text plus typical growth
        // headroom.  Reusing the old buffer across setText calls is dangerous:
        // a delete on the freshly-loaded piece can shift its offset, and when
        // subsequent typing coalesces into that shifted piece the combined
        // offset+length may exceed the still-old buffer.size before the next
        // resize, producing an IndexOutOfBoundsException in getText.
        addBuffer = CharArray(maxOf(64, newText.length + (newText.length ushr 1) + 64))
        addLength = 0
        totalLength = newText.length
        invalidateCache()
        if (newText.isNotEmpty()) {
            appendToAddBuffer(newText)
            pieces.add(Piece(BufferType.ADD, 0, newText.length))
        }
    }

    val length: Int get() = totalLength
}
