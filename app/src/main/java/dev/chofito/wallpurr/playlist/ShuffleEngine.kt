package dev.chofito.wallpurr.playlist

import kotlin.random.Random

data class ShuffleState(
    val remainingIds: List<String>,
    val historyIds: List<String>,
    val currentImageId: String?,
)

object ShuffleEngine {
    fun start(
        imageIds: List<String>,
        random: Random = Random.Default,
        shuffle: Boolean = false,
    ): ShuffleState {
        if (imageIds.isEmpty()) {
            return ShuffleState(emptyList(), emptyList(), null)
        }
        val remaining = imageIds.toMutableList()
        if (shuffle) remaining.shuffle(random)
        val current = remaining.removeAt(0)
        return ShuffleState(remaining, emptyList(), current)
    }

    /**
     * Aligns a persisted cursor with the playlist's current membership without restarting the
     * cycle. Removed ids disappear, new ids are appended to the upcoming order (preserving an
     * already shuffled order) and the current image is kept while it still belongs to the
     * playlist. When the current image was removed, the next upcoming image takes its place.
     */
    fun reconcile(state: ShuffleState, allIds: List<String>): ShuffleState {
        if (allIds.isEmpty()) return ShuffleState(emptyList(), emptyList(), null)
        val allowed = allIds.toSet()
        val current = state.currentImageId?.takeIf { it in allowed }
        val seen = mutableSetOf<String>()
        current?.let(seen::add)
        val history = state.historyIds.filter { it in allowed && seen.add(it) }
        val remaining = buildList {
            state.remainingIds.forEach { id -> if (id in allowed && seen.add(id)) add(id) }
            allIds.forEach { id -> if (seen.add(id)) add(id) }
        }
        return when {
            current != null -> ShuffleState(remaining, history, current)
            remaining.isNotEmpty() -> ShuffleState(remaining.drop(1), history, remaining.first())
            else -> ShuffleState(history.drop(1), emptyList(), history.first())
        }
    }

    fun next(
        state: ShuffleState,
        allIds: List<String>,
        random: Random = Random.Default,
    ): ShuffleState {
        if (allIds.isEmpty()) {
            return ShuffleState(emptyList(), emptyList(), null)
        }
        val clean = reconcile(state, allIds)
        // A removed current image was already replaced by the next upcoming one; advancing again
        // would silently skip an item.
        if (clean.currentImageId != state.currentImageId) return clean
        val remaining = clean.remainingIds.toMutableList()
        val history = clean.historyIds.toMutableList()
        clean.currentImageId?.let { history.add(it) }
        if (remaining.isEmpty()) {
            val allowed = allIds.toSet()
            val cycle = (history + listOfNotNull(clean.currentImageId))
                .filter { it in allowed }
                .distinct()
                .ifEmpty { allIds }
            val ordered = if (cycle.size == allIds.size) cycle else allIds
            val current = ordered.first()
            return ShuffleState(ordered.drop(1), emptyList(), current)
        }
        val current = remaining.removeAt(0)
        return ShuffleState(remaining, history, current)
    }

    fun previous(state: ShuffleState, allIds: List<String>): ShuffleState {
        if (allIds.isEmpty()) return ShuffleState(emptyList(), emptyList(), null)
        val allowed = allIds.toSet()
        val cycle = buildList {
            val seen = mutableSetOf<String>()
            (state.historyIds + listOfNotNull(state.currentImageId) + state.remainingIds)
                .forEach { id ->
                    if (id in allowed && seen.add(id)) add(id)
                }
            allIds.forEach { id ->
                if (seen.add(id)) add(id)
            }
        }
        if (cycle.isEmpty()) return ShuffleState(emptyList(), emptyList(), null)

        val currentIndex = cycle.indexOf(state.currentImageId).takeIf { it >= 0 } ?: 0
        val previousIndex = if (currentIndex == 0) cycle.lastIndex else currentIndex - 1
        return ShuffleState(
            remainingIds = cycle.drop(previousIndex + 1),
            historyIds = cycle.take(previousIndex),
            currentImageId = cycle[previousIndex],
        )
    }

    /**
     * Moves directly to [imageId] without rebuilding the cycle. The items before the selected
     * image remain reachable through Previous and the items after it remain the upcoming order,
     * including an already shuffled order.
     */
    fun select(
        state: ShuffleState,
        imageId: String,
        allIds: List<String>,
    ): ShuffleState {
        if (imageId !in allIds) return state
        val allowed = allIds.toSet()
        val cycle = buildList {
            val seen = mutableSetOf<String>()
            (state.historyIds + listOfNotNull(state.currentImageId) + state.remainingIds)
                .forEach { id ->
                    if (id in allowed && seen.add(id)) add(id)
                }
            allIds.forEach { id ->
                if (seen.add(id)) add(id)
            }
        }
        val selectedIndex = cycle.indexOf(imageId)
        if (selectedIndex < 0) return state
        return ShuffleState(
            remainingIds = cycle.drop(selectedIndex + 1),
            historyIds = cycle.take(selectedIndex),
            currentImageId = imageId,
        )
    }
}

enum class WallpaperTarget {
    HOME,
    LOCK,
    BOTH,
    ;

    companion object {
        fun from(value: String?): WallpaperTarget =
            entries.firstOrNull { it.name == value } ?: BOTH
    }
}
