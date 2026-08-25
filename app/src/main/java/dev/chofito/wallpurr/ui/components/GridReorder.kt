package dev.chofito.wallpurr.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class GridReorderState internal constructor(
    private val scope: CoroutineScope,
    private val onCommit: (List<String>) -> Unit,
) {
    var orderIds by mutableStateOf(emptyList<String>())
        internal set
    var draggingId by mutableStateOf<String?>(null)
        private set
    var dragOffset by mutableStateOf(Offset.Zero)
        private set

    private val bounds = mutableMapOf<String, Rect>()
    private var pendingOrigin: Offset? = null

    fun sync(ids: List<String>) {
        if (draggingId == null) orderIds = ids
    }

    internal fun reportPosition(id: String, topLeft: Offset, size: androidx.compose.ui.geometry.Size) {
        val origin = pendingOrigin
        if (origin != null && id == draggingId) {
            dragOffset += origin - topLeft
            pendingOrigin = null
        }
        bounds[id] = Rect(topLeft, size)
    }

    internal fun onStart(id: String) {
        draggingId = id
        dragOffset = Offset.Zero
        pendingOrigin = null
    }

    internal fun onDrag(amount: Offset, gridState: LazyGridState) {
        val id = draggingId ?: return
        dragOffset += amount
        val current = bounds[id] ?: return
        val finger = current.topLeft + dragOffset + Offset(current.width / 2f, current.height / 2f)
        val overId = bounds.entries.firstOrNull { (key, rect) ->
            key != id && key != HEADER_KEY && rect.contains(finger)
        }?.key
        if (overId != null) {
            val list = orderIds.toMutableList()
            val from = list.indexOf(id)
            val to = list.indexOf(overId)
            if (from >= 0 && to >= 0 && from != to) {
                pendingOrigin = current.topLeft
                list.removeAt(from)
                list.add(to, id)
                orderIds = list
            }
        }
        val viewport = gridState.layoutInfo
        val y = finger.y
        val edge = 96f
        when {
            y < viewport.viewportStartOffset + edge -> scope.launch { gridState.scrollBy(-36f) }
            y > viewport.viewportEndOffset - edge -> scope.launch { gridState.scrollBy(36f) }
        }
    }

    internal fun onEnd() {
        if (draggingId == null) return
        draggingId = null
        dragOffset = Offset.Zero
        pendingOrigin = null
        onCommit(orderIds)
    }

    companion object {
        const val HEADER_KEY = "playlist-header"
    }
}

@Composable
fun rememberGridReorderState(onCommit: (List<String>) -> Unit): GridReorderState {
    val scope = rememberCoroutineScope()
    return remember { GridReorderState(scope, onCommit) }
}

fun Modifier.gridReorderItem(
    state: GridReorderState,
    gridState: LazyGridState,
    id: String,
    enabled: Boolean,
): Modifier {
    if (!enabled) return this
    return this
        .onGloballyPositioned { coords ->
            state.reportPosition(id, coords.positionInParent(), coords.size.toSize())
        }
        .pointerInput(id) {
            try {
                detectDragGestures(
                    onDragStart = { state.onStart(id) },
                    onDragEnd = { state.onEnd() },
                    onDragCancel = { state.onEnd() },
                    onDrag = { change, amount ->
                        change.consume()
                        state.onDrag(amount, gridState)
                    },
                )
            } finally {
                state.onEnd()
            }
        }
}
