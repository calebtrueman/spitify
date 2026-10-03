package com.localfy.app.data.podcast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class SpokenSearchTest {
    @Test fun clearingSearchRejectsAReplyEvenWhenItsRequestIgnoresCancellation() {
        val response = CompletableDeferred<List<String>>()
        val search = SpokenSearch(CoroutineScope(Dispatchers.Unconfined)) { _: String -> withContext(NonCancellable) { response.await() } }
        search.search("old request")
        assertTrue(search.state.value.searching)
        search.clear()
        response.complete(listOf("old result"))
        assertNull(search.state.value.results)
        assertFalse(search.state.value.searching)
    }

    @Test fun newestRequestKeepsItsResultWhenAnOlderReplyArrivesLater() {
        val first = CompletableDeferred<List<String>>()
        val second = CompletableDeferred<List<String>>()
        val search = SpokenSearch(CoroutineScope(Dispatchers.Unconfined)) { term: String -> withContext(NonCancellable) { if (term == "first") first.await() else second.await() } }
        search.search("first")
        search.search("second")
        second.complete(listOf("new result"))
        first.complete(listOf("old result"))
        assertEquals(listOf("new result"), search.state.value.results)
        assertFalse(search.state.value.searching)
    }

    @Test fun failureOffersRetryAndARealEmptyResultHasNoError() {
        var fail = true
        val search = SpokenSearch(CoroutineScope(Dispatchers.Unconfined)) { _: String ->
            if (fail) throw java.io.IOException("offline")
            emptyList<String>()
        }
        search.search("show")
        assertNotNull(search.state.value.error)
        assertNull(search.state.value.results)
        assertFalse(search.state.value.searching)
        fail = false
        search.search("show")
        assertEquals(emptyList<String>(), search.state.value.results)
        assertNull(search.state.value.error)
    }
}
