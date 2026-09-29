package com.spautifaille.ui.search

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Historique en mémoire, avec les mêmes règles ([SearchHistoryRepository.push]) que l'implémentation DataStore. */
class FakeSearchHistoryRepository(initial: List<String> = emptyList()) : SearchHistoryRepository {
    private val state = MutableStateFlow(initial)
    override val queries: Flow<List<String>> = state

    val current: List<String> get() = state.value

    override suspend fun add(query: String) {
        state.value = SearchHistoryRepository.push(state.value, query)
    }

    override suspend fun remove(query: String) {
        state.value = state.value.filterNot { it.equals(query, ignoreCase = true) }
    }

    override suspend fun clear() {
        state.value = emptyList()
    }
}
