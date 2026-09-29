package com.spautifaille.ui.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Nom du fichier DataStore (et qualificatif Hilt) de l'historique de recherche. */
const val SEARCH_HISTORY_DATASTORE_NAME = "search_history"

/** Historique des dernières recherches (les plus récentes d'abord, [MAX_QUERIES] au plus). */
interface SearchHistoryRepository {
    val queries: Flow<List<String>>

    /** Place [query] en tête (sans doublon, insensible à la casse). Sans effet si elle est vide. */
    suspend fun add(query: String)

    suspend fun remove(query: String)

    suspend fun clear()

    companion object {
        const val MAX_QUERIES = 10

        /** Règle de mise à jour de l'historique, pure : nettoie [query], la déduplique et plafonne la liste. */
        fun push(history: List<String>, query: String): List<String> {
            val clean = query.trim().replace(WHITESPACE_RUN, " ")
            if (clean.isEmpty()) return history
            return (listOf(clean) + history.filterNot { it.equals(clean, ignoreCase = true) }).take(MAX_QUERIES)
        }

        private val WHITESPACE_RUN = Regex("\\s+")
    }
}

/** Historique dans un `DataStore<Preferences>` dédié (fichier `search_history`), sérialisé en JSON. */
@Singleton
class DataStoreSearchHistoryRepository @Inject constructor(
    @Named(SEARCH_HISTORY_DATASTORE_NAME) private val dataStore: DataStore<Preferences>,
) : SearchHistoryRepository {

    override val queries: Flow<List<String>> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { decode(it[KEY]) }
        .distinctUntilChanged()

    override suspend fun add(query: String) {
        dataStore.edit { prefs -> prefs[KEY] = encode(SearchHistoryRepository.push(decode(prefs[KEY]), query)) }
    }

    override suspend fun remove(query: String) {
        dataStore.edit { prefs ->
            prefs[KEY] = encode(decode(prefs[KEY]).filterNot { it.equals(query, ignoreCase = true) })
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            Json.decodeFromString(LIST_SERIALIZER, raw).take(SearchHistoryRepository.MAX_QUERIES)
        } catch (e: Exception) {
            // Contenu illisible : l'historique est un confort, on repart de zéro.
            emptyList()
        }
    }

    private fun encode(queries: List<String>): String = Json.encodeToString(LIST_SERIALIZER, queries)

    private companion object {
        val KEY = stringPreferencesKey("recent_queries")
        val LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}
