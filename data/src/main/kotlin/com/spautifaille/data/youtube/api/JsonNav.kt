package com.spautifaille.data.youtube.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Navigation tolérante dans les réponses InnerTube : un chemin absent renvoie `null` (jamais d'exception), c'est
 * l'appelant qui décide si l'absence est une erreur de synchro claire (voir [YouTubeParseException]).
 */

/** Suit un chemin de clés (`String`) et d'indices (`Int`) ; `null` dès qu'une étape manque. */
internal fun JsonElement?.at(vararg path: Any): JsonElement? {
    var current: JsonElement? = this
    for (step in path) {
        current = when (step) {
            is String -> (current as? JsonObject)?.get(step)
            is Int -> (current as? JsonArray)?.getOrNull(step)
            else -> null
        }
        if (current == null || current is JsonNull) return null
    }
    return current
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.boolean(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

/**
 * Texte d'un champ InnerTube : `{"runs":[{"text":"…"}, …]}` (runs concaténés) ou `{"simpleText":"…"}`.
 */
internal fun JsonElement?.text(): String? {
    val obj = this as? JsonObject ?: return null
    obj["simpleText"].string()?.let { return it }
    val runs = obj["runs"].asArray() ?: return null
    val joined = runs.mapNotNull { it.at("text").string() }.joinToString("")
    return joined.ifEmpty { null }
}

/** Premier run d'un champ texte (le titre d'une ligne, sans les séparateurs ni les suffixes). */
internal fun JsonElement?.firstRunText(): String? = at("runs", 0, "text").string()

/** Dernière URL de la liste de vignettes (la plus grande en général). */
internal fun JsonElement?.lastThumbnailUrl(): String? =
    this.asArray()?.lastOrNull().at("url").string()

/**
 * Cherche en profondeur (parcours en largeur limité par [maxDepth]) la première valeur associée à [key], dans les
 * objets imbriqués. Sert à retrouver un conteneur (`gridRenderer`, `musicShelfRenderer`…) dont la position exacte
 * varie selon le type de compte et de page.
 */
internal fun JsonElement?.findFirst(key: String, maxDepth: Int = 12): JsonElement? {
    var level: List<JsonElement> = listOfNotNull(this)
    var depth = 0
    while (level.isNotEmpty() && depth <= maxDepth) {
        val next = ArrayList<JsonElement>()
        for (node in level) {
            when (node) {
                is JsonObject -> {
                    node[key]?.takeIf { it !is JsonNull }?.let { return it }
                    next.addAll(node.values)
                }
                is JsonArray -> next.addAll(node)
                else -> Unit
            }
        }
        level = next
        depth++
    }
    return null
}
