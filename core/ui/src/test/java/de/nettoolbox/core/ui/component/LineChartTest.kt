package de.nettoolbox.core.ui.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LineChartTest {

    @Test
    fun `a series shorter than the budget is returned untouched`() {
        val points = listOf(1f, 2f, 3f)
        assertSame(points, downsample(points, maxPoints = 10))
    }

    @Test
    fun `downsampling produces exactly the requested number of buckets`() {
        val points = List(1000) { it.toFloat() }
        assertEquals(240, downsample(points, maxPoints = 240).size)
    }

    @Test
    fun `MIN keeps the worst value in each bucket`() {
        val points = listOf(10f, -5f, 10f, 10f, -8f, 10f)

        assertEquals(listOf(-5f, -8f), downsample(points, maxPoints = 2, strategy = BucketStrategy.MIN))
    }

    @Test
    fun `MAX keeps the spike in each bucket`() {
        val points = listOf(1f, 90f, 1f, 1f, 70f, 1f)

        assertEquals(listOf(90f, 70f), downsample(points, maxPoints = 2, strategy = BucketStrategy.MAX))
    }

    @Test
    fun `MEAN averages each bucket`() {
        val points = listOf(0f, 2f, 4f, 6f)

        assertEquals(listOf(1f, 5f), downsample(points, maxPoints = 2, strategy = BucketStrategy.MEAN))
    }

    @Test
    fun `every bucket holds at least one sample when maxPoints approaches the size`() {
        val points = List(7) { it.toFloat() }

        val reduced = downsample(points, maxPoints = 6, strategy = BucketStrategy.MIN)

        assertEquals(6, reduced.size)
    }

    @Test
    fun `a non-positive budget is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            downsample(listOf(1f, 2f), maxPoints = 0)
        }
    }
}
