package dev.chofito.wallpurr.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageLayoutTest {
    @Test
    fun coverScalesToFillViewport() {
        val layout = ImageLayout.layout(
            imageWidth = 100,
            imageHeight = 100,
            viewportWidth = 200f,
            viewportHeight = 400f,
            transform = ImageTransform(fitMode = FitMode.COVER),
        )
        assertEquals(4f, layout.scaleX, 0.01f)
        assertEquals(4f, layout.scaleY, 0.01f)
    }

    @Test
    fun containFitsInsideViewport() {
        val layout = ImageLayout.layout(
            imageWidth = 100,
            imageHeight = 100,
            viewportWidth = 200f,
            viewportHeight = 400f,
            transform = ImageTransform(fitMode = FitMode.CONTAIN),
        )
        assertEquals(2f, layout.scaleX, 0.01f)
        assertTrue(layout.translateY > 0f)
    }

    @Test
    fun panStopsAtCoveredEdge() {
        val transform = ImageLayout.clampToViewport(
            imageWidth = 100,
            imageHeight = 100,
            viewportWidth = 200f,
            viewportHeight = 400f,
            transform = ImageTransform(fitMode = FitMode.COVER, offsetX = 1f, offsetY = 1f),
        )
        assertEquals(1f, transform.offsetX, 0.01f)
        assertEquals(0f, transform.offsetY, 0.01f)
    }

    @Test
    fun extremePanStillCoversViewport() {
        val transform = ImageLayout.clampToViewport(
            imageWidth = 100,
            imageHeight = 100,
            viewportWidth = 200f,
            viewportHeight = 400f,
            transform = ImageTransform(fitMode = FitMode.COVER, zoom = 3f, offsetX = 40f, offsetY = 40f),
        )
        val layout = ImageLayout.layout(
            imageWidth = 100,
            imageHeight = 100,
            viewportWidth = 200f,
            viewportHeight = 400f,
            transform = transform,
        )
        val drawnW = 100f * layout.scaleX
        val drawnH = 100f * layout.scaleY
        assertTrue(layout.translateX <= 0.01f)
        assertTrue(layout.translateY <= 0.01f)
        assertTrue(layout.translateX + drawnW >= 199.99f)
        assertTrue(layout.translateY + drawnH >= 399.99f)
    }
}
