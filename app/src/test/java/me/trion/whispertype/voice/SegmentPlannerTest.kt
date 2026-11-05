package me.trion.whispertype.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentPlannerTest {
    private val delivered = mutableListOf<Pair<Int, SegmentBoundary>>()

    private fun planner() = SegmentPlanner(28_000, 16_000) { pcm, boundary ->
        delivered += pcm.size / (16_000 * 2) to boundary
    }

    private fun seconds(value: Int) = ByteArray(value * 16_000 * 2)

    @Test
    fun `10 10 10 delivers 20 seconds and keeps the last segment`() {
        val planner = planner()
        planner.add(seconds(10), pausedBefore = false)
        planner.add(seconds(10), pausedBefore = true)
        assertEquals(0, delivered.size)

        planner.add(seconds(10), pausedBefore = true)
        assertEquals(listOf(20 to SegmentBoundary.Pause), delivered)

        planner.finish(endedOnPause = true)
        assertEquals(
            listOf(20 to SegmentBoundary.Pause, 10 to SegmentBoundary.Pause),
            delivered,
        )
    }

    @Test
    fun `2 2 20 delivers all three segments`() {
        val planner = planner()
        planner.add(seconds(2), pausedBefore = false)
        planner.add(seconds(2), pausedBefore = true)
        planner.add(seconds(20), pausedBefore = true)

        assertEquals(listOf(24 to SegmentBoundary.Pause), delivered)
    }

    @Test
    fun `a single 30 second segment is delivered at once as hard`() {
        val planner = planner()
        planner.add(seconds(30), pausedBefore = false)

        assertEquals(listOf(30 to SegmentBoundary.Hard), delivered)
    }

    @Test
    fun `an excluded segment after a hard flush makes the cut hard`() {
        val planner = planner()
        planner.add(seconds(10), pausedBefore = false)
        planner.add(seconds(10), pausedBefore = true)
        planner.add(seconds(10), pausedBefore = false)

        assertEquals(listOf(20 to SegmentBoundary.Hard), delivered)
    }

    @Test
    fun `stop delivers the remainder`() {
        val planner = planner()
        planner.add(seconds(5), pausedBefore = false)
        planner.add(seconds(5), pausedBefore = true)
        assertEquals(0, delivered.size)

        planner.finish(endedOnPause = true)
        assertEquals(listOf(10 to SegmentBoundary.Pause), delivered)
    }

    @Test
    fun `a part shorter than 8 seconds is delivered only at stop`() {
        val planner = planner()
        planner.add(seconds(5), pausedBefore = false)
        assertEquals(0, delivered.size)

        planner.finish(endedOnPause = false)
        assertEquals(listOf(5 to SegmentBoundary.Hard), delivered)
    }

    @Test
    fun `finish twice delivers the remainder once`() {
        val planner = planner()
        planner.add(seconds(3), pausedBefore = false)
        planner.finish(endedOnPause = true)
        planner.finish(endedOnPause = true)

        assertEquals(1, delivered.size)
    }
}
