package de.nettoolbox.core.common.result

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class OutcomeTest {

    @Test
    fun `asOutcome emits Loading before the first value`() = runTest {
        flowOf(42).asOutcome().test {
            assertEquals(Outcome.Loading, awaitItem())
            assertEquals(Outcome.Success(42), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `asOutcome turns an upstream failure into a Failure value`() = runTest {
        val failing = flow<Int> { throw UnknownHostException("nope.invalid") }

        failing.asOutcome().test {
            assertEquals(Outcome.Loading, awaitItem())
            val failure = assertInstanceOf(Outcome.Failure::class.java, awaitItem())
            assertEquals(ErrorReason.HOST_UNREACHABLE, failure.error.reason)
            awaitComplete()
        }
    }

    @Test
    fun `suspendRunCatching maps a timeout onto the error domain`() {
        val outcome = suspendRunCatching<Int> { throw SocketTimeoutException("too slow") }

        val failure = assertInstanceOf(Outcome.Failure::class.java, outcome)
        assertEquals(ErrorReason.TIMEOUT, failure.error.reason)
    }

    @Test
    fun `suspendRunCatching rethrows cancellation instead of swallowing it`() {
        assertThrows(CancellationException::class.java) {
            suspendRunCatching<Int> { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `map leaves Loading and Failure untouched`() {
        assertEquals(Outcome.Loading, Outcome.Loading.map { 1 })

        val error = NetToolboxError(ErrorReason.PORT_IN_USE)
        assertEquals(Outcome.Failure(error), Outcome.Failure(error).map { 1 })

        assertEquals(Outcome.Success(4), Outcome.Success(2).map { it * 2 })
    }
}
