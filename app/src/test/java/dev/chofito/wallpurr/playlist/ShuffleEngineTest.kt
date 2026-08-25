package dev.chofito.wallpurr.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleEngineTest {
    @Test
    fun startPicksOneAndLeavesTheRest() {
        val state = ShuffleEngine.start(listOf("a", "b", "c"), Random(1))
        assertTrue(state.currentImageId in listOf("a", "b", "c"))
        assertEquals(2, state.remainingIds.size)
        assertTrue(state.historyIds.isEmpty())
    }

    @Test
    fun nextDoesNotRepeatUntilExhausted() {
        val ids = listOf("a", "b", "c")
        var state = ShuffleEngine.start(ids, Random(2))
        val seen = mutableListOf(state.currentImageId)
        repeat(2) {
            state = ShuffleEngine.next(state, ids, Random(2))
            seen += state.currentImageId
        }
        assertEquals(ids.toSet(), seen.toSet())
    }

    @Test
    fun reshuffleAvoidsImmediateRepeatWhenPossible() {
        val ids = listOf("a", "b")
        var state = ShuffleEngine.start(ids, Random(3))
        val firstCycleLast = run {
            state = ShuffleEngine.next(state, ids, Random(4))
            state.currentImageId
        }
        state = ShuffleEngine.next(state, ids, Random(5))
        if (ids.size > 1) {
            assertNotEquals(firstCycleLast, state.currentImageId)
        }
    }

    @Test
    fun loopsPlaylistOrderForever() {
        val ids = listOf("1", "2", "3", "4")
        var state = ShuffleEngine.start(ids)
        val seen = mutableListOf<String?>()
        repeat(8) {
            seen += state.currentImageId
            state = ShuffleEngine.next(state, ids)
        }
        assertEquals(listOf("1", "2", "3", "4", "1", "2", "3", "4"), seen)
    }

    @Test
    fun previousReturnsToLastCurrent() {
        val ids = listOf("a", "b", "c")
        var state = ShuffleEngine.start(ids, Random(9))
        val first = state.currentImageId
        state = ShuffleEngine.next(state, ids, Random(9))
        state = ShuffleEngine.previous(state, ids)
        assertEquals(first, state.currentImageId)
    }
}
