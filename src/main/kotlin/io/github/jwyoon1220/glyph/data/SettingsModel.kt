package io.github.jwyoon1220.glyph.data

import kotlinx.serialization.Serializable

@Serializable
data class Character(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String> = emptyList()
)

@Serializable
data class Place(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String> = emptyList()
)

@Serializable
data class Item(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String> = emptyList()
)

@Serializable
data class WorldSetting(
    val id: String,
    val title: String,
    val content: String
)

@Serializable
data class ProjectSettings(
    val projectName: String,
    val characters: List<Character> = emptyList(),
    val places: List<Place> = emptyList(),
    val items: List<Item> = emptyList(),
    val worldSettings: List<WorldSetting> = emptyList()
)
