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

    @Test
    fun reconcileKeepsCurrentAndShuffledOrderWhenImagesAreAdded() {
        val state = ShuffleState(listOf("c", "a"), listOf("d"), "b")
        val result = ShuffleEngine.reconcile(state, listOf("a", "b", "c", "d", "e"))
        assertEquals("b", result.currentImageId)
        assertEquals(listOf("c", "a", "e"), result.remainingIds)
        assertEquals(listOf("d"), result.historyIds)
    }

    @Test
    fun reconcileDropsDeletedIdsAndReplacesRemovedCurrent() {
        val state = ShuffleState(listOf("c", "d"), listOf("a"), "b")
        val result = ShuffleEngine.reconcile(state, listOf("a", "c", "d"))
        assertEquals("c", result.currentImageId)
        assertEquals(listOf("d"), result.remainingIds)
        assertEquals(listOf("a"), result.historyIds)
    }

    @Test
    fun reconcileIsStableWhenMembershipIsUnchanged() {
        val state = ShuffleState(listOf("c"), listOf("a"), "b")
        assertEquals(state, ShuffleEngine.reconcile(state, listOf("a", "b", "c")))
    }

    @Test
    fun nextNeverReturnsADeletedImage() {
        val state = ShuffleState(listOf("gone", "c"), listOf("a"), "b")
        val next = ShuffleEngine.next(state, listOf("a", "b", "c"))
        assertEquals("c", next.currentImageId)
    }

    @Test
    fun nextDoesNotSkipWhenTheCurrentImageWasDeleted() {
        val state = ShuffleState(listOf("c", "d"), listOf("a"), "b")
        val next = ShuffleEngine.next(state, listOf("a", "c", "d"))
        assertEquals("c", next.currentImageId)
    }

    @Test
    fun cycleKeepsShuffledOrderAfterAnImageIsAdded() {
        val ids = listOf("a", "b", "c")
        var state = ShuffleState(listOf("a", "b"), emptyList(), "c")
        state = ShuffleEngine.next(state, ids + "d")
        state = ShuffleEngine.next(state, ids + "d")
        state = ShuffleEngine.next(state, ids + "d")
        assertEquals("d", state.currentImageId)
        state = ShuffleEngine.next(state, ids + "d")
        assertEquals("c", state.currentImageId)
    }
}
