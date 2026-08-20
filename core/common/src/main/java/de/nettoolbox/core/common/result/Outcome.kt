package de.nettoolbox.core.common.result

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.coroutines.cancellation.CancellationException

/**
 * Three-state result for anything that is loaded or measured.
 *
 * Loading is part of the type instead of a separate boolean so a screen can never
 * render "no results" while a scan is still running - a distinction that matters
 * when an empty result set is itself a valid finding.
 */
sealed interface Outcome<out T> {

    data object Loading : Outcome<Nothing>

    data class Success<out T>(val data: T) : Outcome<T>

    data class Failure(val error: NetToolboxError) : Outcome<Nothing>
}

val <T> Outcome<T>.dataOrNull: T?
    get() = (this as? Outcome.Success)?.data

val Outcome<*>.errorOrNull: NetToolboxError?
    get() = (this as? Outcome.Failure)?.error

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(data))
    is Outcome.Failure -> this
    Outcome.Loading -> Outcome.Loading
}

/**
 * Wraps a flow so failures become values instead of terminating the collection.
 * Cancellation is rethrown untouched.
 */
fun <T> Flow<T>.asOutcome(): Flow<Outcome<T>> = this
    .map<T, Outcome<T>> { Outcome.Success(it) }
    .onStart { emit(Outcome.Loading) }
    .catch { throwable ->
        if (throwable is CancellationException) throw throwable
        emit(Outcome.Failure(throwable.toNetToolboxError()))
    }

/**
 * [runCatching] that does not swallow cancellation. Every native and socket call
 * in this app goes through this instead of the stdlib version.
 */
inline fun <T> suspendRunCatching(block: () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (throwable: Throwable) {
    Outcome.Failure(throwable.toNetToolboxError())
}
