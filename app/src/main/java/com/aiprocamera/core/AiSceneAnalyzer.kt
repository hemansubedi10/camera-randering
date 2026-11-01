package com.aiprocamera.core

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

data class SceneMetrics(
    val luxEstimate: Float,
    val dynamicRange: Float,
    val sceneType: String,
    val recommendedStyle: ColorStyle,
    val requiresBurstFusion: Boolean,
    val histogramBins: IntArray = IntArray(32)
)

class AiSceneAnalyzer(
    private val onMetricsUpdated: (SceneMetrics) -> Unit
) : ImageAnalysis.Analyzer {

    private var frameSkip = 0

    override fun analyze(image: ImageProxy) {
        if (frameSkip++ % 3 != 0) {
            image.close()
            return
        }

        val buffer: ByteBuffer = image.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val width = image.width
        val height = image.height
        val step = 8 // Subsample grid

        var totalLum = 0L
        var minLum = 255
        var maxLum = 0
        var samples = 0
        val bins = IntArray(32)

        for (y in 0 until height step step) {
            val rowOffset = y * width
            for (x in 0 until width step step) {
                val pixelLum = data[rowOffset + x].toInt() and 0xFF
                totalLum += pixelLum
                if (pixelLum < minLum) minLum = pixelLum
                if (pixelLum > maxLum) maxLum = pixelLum
                val binIdx = (pixelLum / 8).coerceIn(0, 31)
                bins[binIdx]++
                samples++
            }
        }

        val avgLum = if (samples > 0) totalLum.toFloat() / samples.toFloat() else 128.0f
        val dr = (maxLum.coerceAtLeast(1).toFloat()) / (minLum.coerceAtLeast(1).toFloat())

        val sceneType: String
        val recommendedStyle: ColorStyle
        val requiresBurst: Boolean

        when {
            avgLum < 20.0f -> {
                sceneType = "Twilight Mountain / Low Light"
                recommendedStyle = ColorStyle.NEPAL_TWILIGHT
                requiresBurst = true
            }
            dr > 4.5f -> {
                sceneType = "Ultra HDR Landscape"
                recommendedStyle = ColorStyle.VIVID_LANDSCAPE
                requiresBurst = true
            }
            avgLum in 20.0f..60.0f -> {
                sceneType = "Blue Hour Dusk"
                recommendedStyle = ColorStyle.NEPAL_TWILIGHT
                requiresBurst = true
            }
            else -> {
                sceneType = "Pro Natural"
                recommendedStyle = ColorStyle.LEICA_NATURAL
                requiresBurst = false
            }
        }

        onMetricsUpdated(
            SceneMetrics(
                luxEstimate = avgLum,
                dynamicRange = dr,
                sceneType = sceneType,
                recommendedStyle = recommendedStyle,
                requiresBurstFusion = requiresBurst,
                histogramBins = bins
            )
        )

        image.close()
    }
}
