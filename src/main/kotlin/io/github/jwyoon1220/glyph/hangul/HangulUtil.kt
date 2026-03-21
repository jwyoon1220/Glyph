package io.github.jwyoon1220.glyph.hangul

object HangulUtil {
    private const val HANGUL_BASE = 0xAC00
    private const val JONGSONG_COUNT = 28

    // 받침이 있으면 true, 없으면 false
    fun hasBatchim(c: Char): Boolean {
        if (c !in '가'..'힣') return false
        val charCode = c.code - HANGUL_BASE
        return charCode % JONGSONG_COUNT != 0
    }

    // ㄹ 받침인지 확인 ( '로/으로' 구분용 )
    fun isRieulBatchim(c: Char): Boolean {
        if (c !in '가'..'힣') return false
        val charCode = c.code - HANGUL_BASE
        return charCode % JONGSONG_COUNT == 8 // 'ㄹ'의 종성 인덱스는 8
    }
    fun pickJosa(word: String, josa: String): String {
        if (word.isEmpty()) return josa

        val lastChar = word.last()
        val hasBatchim = hasBatchim(lastChar)
        val isRieul = isRieulBatchim(lastChar)

        val selectedJosa = when (josa) {
            "이", "가", "이/가" -> if (hasBatchim) "이" else "가"
            "은", "는", "은/는" -> if (hasBatchim) "은" else "는"
            "을", "를", "을/를" -> if (hasBatchim) "을" else "를"
            "이랑", "랑", "이랑/랑" -> if (hasBatchim) "이랑" else "랑"
            "으로", "로", "으로/로" -> if (hasBatchim && !isRieul) "으로" else "로"
            else -> josa // '에서', '부터', '의' 등 형태 변화가 없는 조사
        }

        return word + selectedJosa
    }

    fun detachJosa(word: String): Pair<String, String> {
        if (word.length < 2) return word to ""

        // 1. 분석할 조사 목록 (긴 것부터 매칭)
        val josaList = listOf("에서", "부터", "까지", "이랑", "은", "는", "이", "가", "을", "를", "의", "으로", "로")

        for (josa in josaList) {
            if (word.endsWith(josa)) {
                val stem = word.substring(0, word.length - josa.length)
                val lastChar = stem.last()

                // 2. es-hangul처럼 문법 규칙 검증
                if (isValidPair(lastChar, josa)) {
                    return stem to josa
                }
            }
        }
        return word to ""
    }

    fun isValidPair(lastChar: Char, josa: String): Boolean {
        val hasBatchim = hasBatchim(lastChar)
        val isRieul = isRieulBatchim(lastChar)

        return when (josa) {
            "이", "은", "을", "이랑" -> hasBatchim
            "가", "는", "를", "랑" -> !hasBatchim
            "으로" -> hasBatchim && !isRieul // 받침 있고 'ㄹ'이 아닐 때
            "로" -> !hasBatchim || isRieul   // 받침 없거나 'ㄹ'일 때
            else -> true // '에서', '부터' 등은 받침 상관없음
        }
    }

}