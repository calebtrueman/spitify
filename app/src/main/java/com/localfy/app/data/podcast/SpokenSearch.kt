package com.localfy.app.data.podcast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SpokenSearchState<T>(val results: List<T>? = null, val searching: Boolean = false, val error: String? = null)

/** A late reply must never bring back a cleared search or replace a newer one. */
class SpokenSearch<T>(private val scope: CoroutineScope, private val load: suspend (String) -> List<T>) {
    private val mutableState = MutableStateFlow(SpokenSearchState<T>())
    val state = mutableState.asStateFlow()
    private var request = 0L
    private var job: Job? = null

    fun clear() {
        request++
        job?.cancel()
        mutableState.value = SpokenSearchState()
    }

    fun search(term: String) {
        clear()
        if (term.isBlank()) return
        val current = request
        mutableState.value = SpokenSearchState(searching = true)
        job = scope.launch {
            try {
                val results = load(term.trim())
                if (current == request) mutableState.value = SpokenSearchState(results = results)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (current == request) mutableState.value = SpokenSearchState(error = "Couldn't search. Check your connection and try again.")
            }
        }
    }
}
