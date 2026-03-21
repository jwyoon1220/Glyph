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
        if (index !in 0..totalLength) throw IndexOutOfBoundsException()
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
        val deletionEnd = index + length
        var currentPos = 0

        var i = 0
        while (i < pieces.size) {
            val p = pieces[i]
            val originalLength = p.length
            val pStart = currentPos
            val pEnd = currentPos + originalLength

            if (pEnd > index && pStart < deletionEnd) {
                val overlapStart = maxOf(0, index - pStart)
                val overlapEnd = minOf(originalLength, deletionEnd - pStart)

                // If whole piece is deleted
                if (overlapStart == 0 && overlapEnd == originalLength) {
                    pieces.removeAt(i)
                    currentPos += originalLength
                    if (currentPos >= deletionEnd) break
                    continue
                }
                // If right part is deleted
                else if (overlapEnd == originalLength) {
                    p.length = overlapStart
                } 
                // If left part is deleted
                else if (overlapStart == 0) {
                    pieces[i] = Piece(p.source, p.offset + overlapEnd, originalLength - overlapEnd)
                } 
                // Middle part is deleted (requires to be split)
                else {
                    val rightPiece = Piece(p.source, p.offset + overlapEnd, originalLength - overlapEnd)
                    p.length = overlapStart
                    pieces.add(i + 1, rightPiece)
                    i++ // Skip the newly added piece
                }
            }
            currentPos += originalLength
            if (currentPos >= deletionEnd) break
            i++
        }
    }

    /**
     * 지정된 범위(start ~ end)의 텍스트만 효율적으로 추출합니다.
     * @param start 시작 인덱스 (inclusive)
     * @param end 끝 인덱스 (exclusive)
     */
    fun getText(start: Int, end: Int): String {
        if (start < 0 || end > totalLength || start >= end) return ""

        val builder = StringBuilder(end - start)
        var currentPos = 0

        for (p in pieces) {
            val pieceStart = currentPos
            val pieceEnd = currentPos + p.length

            // 1. 현재 조각이 요청 범위에 포함되는지 확인
            if (pieceEnd > start) {
                // 2. 조각 내에서 실제로 가져올 시작/끝 오프셋 계산
                val relativeStart = maxOf(0, start - pieceStart)
                val relativeEnd = minOf(p.length, end - pieceStart)

                val buffer = if (p.source == BufferType.ORIGINAL) originalBuffer else addBuffer

                // 3. 해당 조각의 필요한 부분만 append
                builder.append(
                    buffer,
                    p.offset + relativeStart,
                    p.offset + relativeEnd
                )
            }

            currentPos = pieceEnd

            // 4. 이미 범위를 벗어났다면 루프 조기 종료 (성능 최적화)
            if (currentPos >= end) break
        }

        return builder.toString()
    }

    val length: Int
        get() = totalLength

    fun setText(newText: String) {
        pieces.clear()
        addBuffer.clear()
        addBuffer.append(newText)
        totalLength = newText.length
        if (newText.isNotEmpty()) {
            pieces.add(Piece(BufferType.ADD, 0, newText.length))
        }
    }
}
