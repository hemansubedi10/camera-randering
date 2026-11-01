package com.aiprocamera.core

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.abs

data class SceneMetrics(
    val luxEstimate: Float,
    val dynamicRange: Float,
    val sceneType: String,
    val recommendedStyle: ColorStyle,
    val requiresBurstFusion: Boolean,
    val focusSharpnessScore: Float = 0f,
    val liveClarityGain: Float = 1.0f,
    val histogramBins: IntArray = IntArray(32)
)

class AiSceneAnalyzer(
    private val onMetricsUpdated: (SceneMetrics) -> Unit
) : ImageAnalysis.Analyzer {

    private var frameSkip = 0

    override fun analyze(image: ImageProxy) {
        if (frameSkip++ % 2 != 0) { // Analyze every 2nd frame for low latency
            image.close()
            return
        }

        val buffer: ByteBuffer = image.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        val width = image.width
        val height = image.height
        val step = 6 // Fast dense sampling grid

        var totalLum = 0L
        var minLum = 255
        var maxLum = 0
        var samples = 0
        val bins = IntArray(32)

        // Laplacian variance / Tenengrad proxy for real-time focus sharpness
        var edgeEnergy = 0L
        var edgeSamples = 0

        val centerX = width / 2
        val centerY = height / 2
        val focusRegionW = width / 3
        val focusRegionH = height / 3

        for (y in step until height - step step step) {
            val rowOffset = y * width
            for (x in step until width - step step step) {
                val idx = rowOffset + x
                val pixelLum = data[idx].toInt() and 0xFF
                totalLum += pixelLum
                if (pixelLum < minLum) minLum = pixelLum
                if (pixelLum > maxLum) maxLum = pixelLum

                val binIdx = (pixelLum / 8).coerceIn(0, 31)
                bins[binIdx]++
                samples++

                // Measure high-frequency contrast near center focus target
                if (abs(x - centerX) < focusRegionW && abs(y - centerY) < focusRegionH) {
                    val rightLum = data[idx + 1].toInt() and 0xFF
                    val bottomLum = data[idx + width].toInt() and 0xFF
                    val dx = abs(rightLum - pixelLum)
                    val dy = abs(bottomLum - pixelLum)
                    edgeEnergy += (dx * dx + dy * dy)
                    edgeSamples++
                }
            }
        }

        val avgLum = if (samples > 0) totalLum.toFloat() / samples.toFloat() else 128.0f
        val dr = (maxLum.coerceAtLeast(1).toFloat()) / (minLum.coerceAtLeast(1).toFloat())

        // Focus sharpness score normalized (0.0 to 100.0)
        val sharpnessScore = if (edgeSamples > 0) {
            ((edgeEnergy.toFloat() / edgeSamples.toFloat()) / 12.0f).coerceIn(0f, 100f)
        } else 50f

        // Adaptive live clarity multiplier:
        // When scene is low-contrast or hazy, increase micro-clarity boost
        val clarityGain = when {
            avgLum < 30f -> 1.45f // Low-light shadow clarity
            dr < 2.5f -> 1.30f    // Flat/hazy lighting boost
            else -> 1.15f
        }

        val sceneType: String
        val recommendedStyle: ColorStyle
        val requiresBurst: Boolean

        when {
            avgLum < 20.0f -> {
                sceneType = "Low Light • Pro Night Vision"
                recommendedStyle = ColorStyle.NEPAL_TWILIGHT
                requiresBurst = true
            }
            dr > 4.5f -> {
                sceneType = "Ultra HDR • High Dynamic Range"
                recommendedStyle = ColorStyle.VIVID_LANDSCAPE
                requiresBurst = true
            }
            avgLum in 20.0f..60.0f -> {
                sceneType = "Golden/Blue Hour • Cinematic"
                recommendedStyle = ColorStyle.NEPAL_TWILIGHT
                requiresBurst = true
            }
            else -> {
                sceneType = "Pro Studio • Authentic Optics"
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
                focusSharpnessScore = sharpnessScore,
                liveClarityGain = clarityGain,
                histogramBins = bins
            )
        )

        image.close()
    }
}
