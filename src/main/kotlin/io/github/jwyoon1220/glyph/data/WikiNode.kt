package io.github.jwyoon1220.glyph.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class WikiNodeType {
    ROOT,
    CATEGORY,
    CHARACTER,
    PROPERTY,
    TEXT_BLOCK
}

@Serializable
data class WikiNode(
    val id: String = UUID.randomUUID().toString(),
    var type: WikiNodeType = WikiNodeType.TEXT_BLOCK,
    var title: String = "newNode",
    var content: String = "",
    var x: Int = 100,
    var y: Int = 100,
    val connectedToIds: MutableList<String> = mutableListOf()
)

@Serializable
data class WikiGraph(
    val nodes: MutableList<WikiNode> = mutableListOf()
)
