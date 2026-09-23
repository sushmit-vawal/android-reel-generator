package com.reelgenerator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class BatchOutput(val source: String, val uri: String)
data class BatchOutcome(val outputs: List<BatchOutput>, val errors: List<String>)

/** Sequential by construction. Prefer unused footage; reuse when the library is small. */
suspend fun runBatch(
    sources: List<String>,
    previous: List<BatchOutput> = emptyList(),
    sourceUseCount: ((String) -> Int)? = null,
    render: suspend (slot: Int, source: String) -> String
): BatchOutcome {
    require(previous.size <= 5)
    val outputs = previous.toMutableList()
    val failed = mutableSetOf<String>()
    val errors = mutableListOf<String>()
    var attempts = 0
    while (outputs.size < 5 && attempts < 20) {
        currentCoroutineContext().ensureActive()
        val source = sources.distinct().filterNot { it in failed }
            .minByOrNull { candidate -> sourceUseCount?.invoke(candidate) ?: outputs.count { it.source == candidate } } ?: break
        attempts++
        try { outputs.add(BatchOutput(source, render(outputs.size, source))) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            failed.add(source)
            errors.add(error.localizedMessage ?: "A video could not be rendered.")
        }
    }
    return BatchOutcome(outputs, errors)
}
