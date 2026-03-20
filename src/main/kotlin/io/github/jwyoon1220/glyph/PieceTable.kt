package io.github.jwyoon1220.glyph

import java.util.ArrayList

/**
 * A highly un-optimized PieceTable for storing and editing text efficiently.
 * The Piece Table maintains an Original Buffer (read-only) and an Add Buffer (append-only).
 * It uses a list of Pieces (spans of text) pointing to either of these buffers.
 */
class PieceTable(initialText: String = "") {
    private val originalBuffer = initialText
    private val addBuffer = StringBuilder()

    enum class BufferType { ORIGINAL, ADD }

    data class Piece(val source: BufferType, val offset: Int, var length: Int)

    private val pieces = ArrayList<Piece>()
    private var totalLength = initialText.length

    init {
        if (initialText.isNotEmpty()) {
            pieces.add(Piece(BufferType.ORIGINAL, 0, initialText.length))
        }
    }

    /**
     * Inserts text at the given index.
     */
    fun insert(index: Int, text: String) {
        if (index < 0 || index > totalLength) throw IndexOutOfBoundsException()
        if (text.isEmpty()) return

        val addOffset = addBuffer.length
        addBuffer.append(text)
        val newPiece = Piece(BufferType.ADD, addOffset, text.length)
        totalLength += text.length

        if (index == 0) {
            pieces.add(0, newPiece)
            return
        }

        var currentPos = 0
        for (i in pieces.indices) {
            val p = pieces[i]
            if (currentPos + p.length > index) {
                // Split the piece
                val splitIndex = index - currentPos
                val rightLength = p.length - splitIndex
                p.length = splitIndex
                
                val rightPiece = Piece(p.source, p.offset + splitIndex, rightLength)
                pieces.add(i + 1, newPiece)
                pieces.add(i + 2, rightPiece)
                return
            }
            currentPos += p.length
        }
        pieces.add(newPiece) // Append to end
    }

    /**
     * Deletes a given number of characters from the index.
     */
    fun delete(index: Int, length: Int) {
        if (index < 0 || length < 0 || index + length > totalLength) throw IndexOutOfBoundsException()
        if (length == 0) return

        totalLength -= length
        var currentPos = 0
        var remainingToDelete = length

        var i = 0
        while (i < pieces.size && remainingToDelete > 0) {
            val p = pieces[i]
            if (currentPos + p.length > index) {
                val overlapStart = maxOf(0, index - currentPos)
                val overlapEnd = minOf(p.length, index + remainingToDelete - currentPos)
                val deleteAmount = overlapEnd - overlapStart

                // If whole piece is deleted
                if (overlapStart == 0 && overlapEnd == p.length) {
                    pieces.removeAt(i)
                    remainingToDelete -= deleteAmount
                    continue
                }

                // If right part is deleted
                if (overlapEnd == p.length) {
                    p.length = overlapStart
                } 
                // If left part is deleted
                else if (overlapStart == 0) {
                    pieces[i] = Piece(p.source, p.offset + overlapEnd, p.length - overlapEnd)
                } 
                // Middle part is deleted (requires split)
                else {
                    val rightPiece = Piece(p.source, p.offset + overlapEnd, p.length - overlapEnd)
                    p.length = overlapStart
                    pieces.add(i + 1, rightPiece)
                    i++ // Skip the newly added piece
                }
                
                remainingToDelete -= deleteAmount
            }
            if (currentPos + p.length <= index) {
               currentPos += p.length
            }
            i++
        }
    }

    /**
     * Retrieves the entire text content.
     */
    fun getText(): String {
        val builder = java.lang.StringBuilder(totalLength)
        for (p in pieces) {
            if (p.source == BufferType.ORIGINAL) {
                builder.append(originalBuffer, p.offset, p.offset + p.length)
            } else {
                builder.append(addBuffer, p.offset, p.offset + p.length)
            }
        }
        return builder.toString()
    }

    val length: Int
        get() = totalLength
}
