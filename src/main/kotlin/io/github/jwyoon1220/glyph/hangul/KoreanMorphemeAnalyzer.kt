package io.github.jwyoon1220.glyph.hangul

import kr.co.shineware.nlp.komoran.constant.DEFAULT_MODEL
import kr.co.shineware.nlp.komoran.core.Komoran
import kr.co.shineware.nlp.komoran.model.KomoranResult
import kr.co.shineware.nlp.komoran.model.Token

class KoreanMorphemeAnalyzer(val sentence: String) {

    // Komoran 엔진은 초기화 비용이 크므로, 클래스 로드 시 한 번만 생성되도록 companion object에 배치합니다.
    companion object {
        private val komoran = Komoran(DEFAULT_MODEL.FULL) // FULL 모델은 가장 정밀한 분석을 제공합니다.
    }

    // 객체 생성 시 자동으로 문장을 분석하고 결과를 저장합니다.
    private val analyzeResult: KomoranResult = komoran.analyze(sentence)

    /**
     * 1. 형태소 분석 결과를 태깅된 평문 형태로 반환합니다.
     * 예: "대한민국/NNP 은/JX 민주공화국/NNP 이/VCP 다/EF ./SF"
     */
    fun getPlainText(): String {
        return analyzeResult.plainText
    }

    /**
     * 2. 분석된 형태소 객체(Token)들의 리스트를 반환합니다.
     * Token 객체에서 형태소(morph), 품사(pos), 시작/끝 인덱스를 추출할 수 있습니다.
     */
    fun getTokens(): List<Token> {
        return analyzeResult.tokenList
    }

    /**
     * 3. (유틸리티) 문장에서 '명사'만 추출합니다.
     * 명령어 파싱이나 키워드 필터링에 유용합니다.
     */
    fun extractNouns(): List<String> {
        // NNG(일반 명사), NNP(고유 명사), NNB(의존 명사) 등 'NN'으로 시작하는 품사 필터링
        return analyzeResult.tokenList
            .filter { it.pos.startsWith("NN") }
            .map { it.morph }
    }

    /**
     * 4. (유틸리티) 문장에서 형태소와 품사를 Pair로 묶어서 반환합니다.
     */
    fun getMorphAndPosList(): List<Pair<String, String>> {
        return analyzeResult.tokenList.map { it.morph to it.pos }
    }
}