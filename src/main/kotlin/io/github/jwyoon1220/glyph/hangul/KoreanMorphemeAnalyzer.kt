package io.github.jwyoon1220.glyph.hangul

import kr.co.shineware.nlp.komoran.constant.DEFAULT_MODEL
import kr.co.shineware.nlp.komoran.core.Komoran
import kr.co.shineware.nlp.komoran.model.Token

/**
 * Thread-safe single instance of Komoran analyzer initialized lazily.
 */
private val komoran by lazy { Komoran(DEFAULT_MODEL.FULL) }

/**
 * Returns Korean tokens for the string using Komoran.
 */
val String.koreanTokens: List<Token>
    get() = if (isBlank()) emptyList() else komoran.analyze(this).tokenList

/**
 * Extracts only noun tokens from the string.
 */
val String.koreanNouns: List<String>
    get() = koreanTokens
        .filter { it.pos.startsWith("NN") }
        .map { it.morph }

/**
 * Returns a list of (morpheme, pos) pairs.
 */
val String.koreanMorphAndPos: List<Pair<String, String>>
    get() = koreanTokens.map { it.morph to it.pos }

/**
 * Returns tag-annotated plain text format.
 */
val String.koreanPlainText: String
    get() = if (isBlank()) "" else komoran.analyze(this).plainText