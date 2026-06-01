package io.github.jwyoon1220.glyph.hangul

private const val HANGUL_BASE = 0xAC00
private const val JONGSONG_COUNT = 28

/**
 * Checks if the character has a final consonant (받침).
 */
fun Char.hasBatchim(): Boolean {
    if (this !in '가'..'힣') return false
    return (code - HANGUL_BASE) % JONGSONG_COUNT != 0
}

/**
 * Checks if the character has a 'ㄹ' (rieul) final consonant.
 */
fun Char.isRieulBatchim(): Boolean {
    if (this !in '가'..'힣') return false
    return (code - HANGUL_BASE) % JONGSONG_COUNT == 8
}

/**
 * Appends the correct Korean particle (조사) to the string.
 */
fun String.pickJosa(josa: String): String {
    if (isEmpty()) return josa

    val lastChar = last()
    val hasBatchim = lastChar.hasBatchim()
    val isRieul = lastChar.isRieulBatchim()

    val selectedJosa = when (josa) {
        "이", "가", "이/가" -> if (hasBatchim) "이" else "가"
        "은", "는", "은/는" -> if (hasBatchim) "은" else "는"
        "을", "를", "을/를" -> if (hasBatchim) "을" else "를"
        "이랑", "랑", "이랑/랑" -> if (hasBatchim) "이랑" else "랑"
        "으로", "로", "으로/로" -> if (hasBatchim && !isRieul) "으로" else "로"
        else -> josa
    }

    return this + selectedJosa
}

/**
 * Separates the stem and the Korean particle (조사) from the string.
 */
fun String.detachJosa(): Pair<String, String> {
    if (length < 2) return this to ""

    val josaList = listOf("에서", "부터", "까지", "이랑", "은", "는", "이", "가", "을", "를", "의", "으로", "로")

    for (josa in josaList) {
        if (endsWith(josa)) {
            val stem = substring(0, length - josa.length)
            if (stem.isNotEmpty()) {
                val lastChar = stem.last()
                if (lastChar.isValidJosa(josa)) {
                    return stem to josa
                }
            }
        }
    }
    return this to ""
}

/**
 * Verifies if the combination of final consonant and the particle is grammatically valid.
 */
fun Char.isValidJosa(josa: String): Boolean {
    val hasBatchim = hasBatchim()
    val isRieul = isRieulBatchim()

    return when (josa) {
        "이", "은", "을", "이랑" -> hasBatchim
        "가", "는", "를", "랑" -> !hasBatchim
        "으로" -> hasBatchim && !isRieul
        "로" -> !hasBatchim || isRieul
        else -> true
    }
}