package com.spautifaille.ui.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SearchHistoryRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.dataStore(file: File = File(folder.root, "search_history.preferences_pb")): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })

    // --- Règle pure -------------------------------------------------------------------------------

    @Test
    fun `push place la requete en tete sans doublon insensible a la casse`() {
        val result = SearchHistoryRepository.push(listOf("b", "a", "c"), "  A ")
        assertEquals(listOf("A", "b", "c"), result)
    }

    @Test
    fun `push plafonne l historique a dix requetes`() {
        val full = (1..10).map { "q$it" }
        val result = SearchHistoryRepository.push(full, "nouvelle")
        assertEquals(10, result.size)
        assertEquals("nouvelle", result.first())
        assertEquals("q9", result.last())
    }

    @Test
    fun `push ignore les requetes vides et normalise les espaces`() {
        val history = listOf("a")
        assertEquals(history, SearchHistoryRepository.push(history, "   "))
        assertEquals(listOf("daft punk", "a"), SearchHistoryRepository.push(history, " daft \n  punk "))
    }

    // --- DataStore ---------------------------------------------------------------------------------

    @Test
    fun `l historique est vide au depart`() = runTest {
        val repository = DataStoreSearchHistoryRepository(dataStore())
        assertTrue(repository.queries.first().isEmpty())
    }

    @Test
    fun `les requetes sont conservees dans l ordre et survivent a une nouvelle instance`() = runTest {
        val file = File(folder.root, "persist.preferences_pb")
        val firstJob = Job()
        val first = DataStoreSearchHistoryRepository(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO), produceFile = { file }),
        )
        first.add("un")
        first.add("deux")
        first.add("trois")
        first.add("UN")
        assertEquals(listOf("UN", "trois", "deux"), first.queries.first())
        // Fin de vie du premier DataStore (DataStore interdit deux instances actives sur le même fichier).
        firstJob.cancelAndJoin()

        // Même fichier relu par un autre DataStore (nouveau démarrage de l'application).
        val secondJob = Job()
        val second = DataStoreSearchHistoryRepository(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO), produceFile = { file }),
        )
        assertEquals(listOf("UN", "trois", "deux"), second.queries.first())
        secondJob.cancelAndJoin()
    }

    @Test
    fun `retirer et effacer`() = runTest {
        val repository = DataStoreSearchHistoryRepository(dataStore())
        repository.add("a")
        repository.add("b")
        repository.add("c")

        repository.remove("B")
        assertEquals(listOf("c", "a"), repository.queries.first())

        repository.clear()
        assertTrue(repository.queries.first().isEmpty())
    }

    @Test
    fun `seules dix requetes sont conservees`() = runTest {
        val repository = DataStoreSearchHistoryRepository(dataStore())
        (1..15).forEach { repository.add("q$it") }

        val queries = repository.queries.first()

        assertEquals(10, queries.size)
        assertEquals("q15", queries.first())
        assertEquals("q6", queries.last())
    }

    @Test
    fun `un contenu illisible donne un historique vide et reste recuperable`() = runTest {
        val store = dataStore()
        store.edit { it[stringPreferencesKey("recent_queries")] = "pas du json" }
        val repository = DataStoreSearchHistoryRepository(store)

        assertTrue(repository.queries.first().isEmpty())
        repository.add("ok")
        assertEquals(listOf("ok"), repository.queries.first())
    }
}
