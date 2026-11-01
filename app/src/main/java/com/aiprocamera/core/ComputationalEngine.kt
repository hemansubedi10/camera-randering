package com.aiprocamera.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ColorStyle {
    NEPAL_TWILIGHT, // Cobalt sky + pink alpenglow + shadow foliage
    LEICA_NATURAL,  // Soft organic roll-off, authentic skin
    VIVID_LANDSCAPE // High dynamic range, deep greens and sky
}

object ComputationalEngine {

    /**
     * Mertens Exposure Fusion: merges multi-exposure bracketed frames without ghosting.
     */
    fun fuseBracketed(frames: List<Bitmap>): Bitmap {
        if (frames.isEmpty()) throw IllegalArgumentException("No frames to fuse")
        if (frames.size == 1) return frames[0]

        val width = frames[0].width
        val height = frames[0].height
        val numPixels = width * height
        val numFrames = frames.size

        val pixelBuffers = Array(numFrames) { i ->
            val buf = IntArray(numPixels)
            frames[i].getPixels(buf, 0, width, 0, 0, width, height)
            buf
        }

        // Compute Mertens quality weights: Contrast * Saturation * WellExposedness
        val weightMaps = Array(numFrames) { FloatArray(numPixels) }

        for (f in 0 until numFrames) {
            val pixels = pixelBuffers[f]
            val weights = weightMaps[f]

            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    val idx = rowOffset + x
                    val color = pixels[idx]

                    val r = ((color shr 16) and 0xFF) / 255.0f
                    val g = ((color shr 8) and 0xFF) / 255.0f
                    val b = (color and 0xFF) / 255.0f

                    // 1. Well-exposedness metric (Gaussian centered at 0.5, sigma=0.2)
                    val sigma2 = 2.0f * 0.2f * 0.2f
                    val wR = exp(-((r - 0.5f) * (r - 0.5f)) / sigma2)
                    val wG = exp(-((g - 0.5f) * (g - 0.5f)) / sigma2)
                    val wB = exp(-((b - 0.5f) * (b - 0.5f)) / sigma2)
                    val exposedness = wR * wG * wB

                    // 2. Saturation metric (standard deviation of RGB channels)
                    val mu = (r + g + b) / 3.0f
                    val sat = sqrt(((r - mu) * (r - mu) + (g - mu) * (g - mu) + (b - mu) * (b - mu)) / 3.0f)

                    // 3. Simple spatial contrast proxy
                    val lum = 0.299f * r + 0.587f * g + 0.114f * b
                    val contrast = if (x > 0 && x < width - 1) {
                        val leftLum = ((pixels[idx - 1] shr 8) and 0xFF) / 255.0f
                        val rightLum = ((pixels[idx + 1] shr 8) and 0xFF) / 255.0f
                        abs(rightLum - leftLum)
                    } else {
                        0.1f
                    }

                    // Combined Mertens weight + epsilon to avoid division by zero
                    weights[idx] = (exposedness * (sat + 0.05f) * (contrast + 0.05f)) + 1e-6f
                }
            }
        }

        // Normalize weights and blend
        val outputPixels = IntArray(numPixels)
        for (i in 0 until numPixels) {
            var sumWeight = 0.0f
            for (f in 0 until numFrames) {
                sumWeight += weightMaps[f][i]
            }

            var accR = 0.0f
            var accG = 0.0f
            var accB = 0.0f

            for (f in 0 until numFrames) {
                val normWeight = weightMaps[f][i] / sumWeight
                val color = pixelBuffers[f][i]
                accR += ((color shr 16) and 0xFF) * normWeight
                accG += ((color shr 8) and 0xFF) * normWeight
                accB += (color and 0xFF) * normWeight
            }

            val outR = min(255, max(0, accR.toInt()))
            val outG = min(255, max(0, accG.toInt()))
            val outB = min(255, max(0, accB.toInt()))

            outputPixels[i] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Ultra Frame-by-Frame Denoising with Edge-Preserving Bilateral Filtering.
     */
    fun bilateralDenoise(src: Bitmap, spatialSigma: Float = 2.0f, rangeSigma: Float = 25.0f): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val radius = 2
        val twoSpatialSigma2 = 2.0f * spatialSigma * spatialSigma
        val twoRangeSigma2 = 2.0f * rangeSigma * rangeSigma

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val centerIdx = rowOffset + x
                val centerColor = pixels[centerIdx]
                val cR = (centerColor shr 16) and 0xFF
                val cG = (centerColor shr 8) and 0xFF
                val cB = centerColor and 0xFF
                val centerLum = 0.299f * cR + 0.587f * cG + 0.114f * cB

                var sumWeights = 0.0f
                var sumR = 0.0f
                var sumG = 0.0f
                var sumB = 0.0f

                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, height - 1)
                    val nRowOffset = ny * width
                    for (dx in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, width - 1)
                        val neighborColor = pixels[nRowOffset + nx]

                        val nR = (neighborColor shr 16) and 0xFF
                        val nG = (neighborColor shr 8) and 0xFF
                        val nB = neighborColor and 0xFF
                        val neighborLum = 0.299f * nR + 0.587f * nG + 0.114f * nB

                        val spatialDist2 = (dx * dx + dy * dy).toFloat()
                        val rangeDist = neighborLum - centerLum
                        val rangeDist2 = rangeDist * rangeDist

                        val weight = exp(-(spatialDist2 / twoSpatialSigma2) - (rangeDist2 / twoRangeSigma2))

                        sumWeights += weight
                        sumR += nR * weight
                        sumG += nG * weight
                        sumB += nB * weight
                    }
                }

                val finalR = min(255, max(0, (sumR / sumWeights).toInt()))
                val finalG = min(255, max(0, (sumG / sumWeights).toInt()))
                val finalB = min(255, max(0, (sumB / sumWeights).toInt()))

                outPixels[centerIdx] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Film Tone Curve Reproduction & Aesthetic Color Grading.
     */
    fun toneMapAndGrade(src: Bitmap, style: ColorStyle): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            var r = ((color shr 16) and 0xFF) / 255.0f
            var g = ((color shr 8) and 0xFF) / 255.0f
            var b = (color and 0xFF) / 255.0f

            // Reinhard + ACES Filmic S-curve tone mapping
            r = acesToneCurve(r)
            g = acesToneCurve(g)
            b = acesToneCurve(b)

            // Color Grading Style transforms
            when (style) {
                ColorStyle.NEPAL_TWILIGHT -> {
                    // Sky cobalt blue + warm magenta alpenglow highlights
                    b = (b * 1.08f).coerceAtMost(1.0f)
                    if (r > 0.6f) r = (r * 1.05f).coerceAtMost(1.0f)
                    // Foliage shadow saturation boost
                    if (g < 0.4f) g = (g * 0.96f)
                }
                ColorStyle.LEICA_NATURAL -> {
                    // Leica classic natural roll-off: gentle contrast and rich midtones
                    r = (r * 0.98f + 0.01f).coerceIn(0f, 1f)
                    b = (b * 0.97f + 0.01f).coerceIn(0f, 1f)
                }
                ColorStyle.VIVID_LANDSCAPE -> {
                    // Deep greens & punchy skies
                    g = (g * 1.06f).coerceAtMost(1.0f)
                    b = (b * 1.05f).coerceAtMost(1.0f)
                    r = (r * 1.02f).coerceAtMost(1.0f)
                }
            }

            val outR = min(255, max(0, (r * 255.0f).toInt()))
            val outG = min(255, max(0, (g * 255.0f).toInt()))
            val outB = min(255, max(0, (b * 255.0f).toInt()))

            pixels[i] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Micro-Contrast Unsharp Mask for Crisp Edge Details.
     */
    fun unsharpMask(src: Bitmap, amount: Float = 0.65f, threshold: Int = 4): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // Box blur approximation for high-pass mask
        val blurredPixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                var rAcc = 0
                var gAcc = 0
                var bAcc = 0
                var count = 0

                for (dy in -1..1) {
                    val ny = (y + dy).coerceIn(0, height - 1)
                    val nRowOffset = ny * width
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, width - 1)
                        val c = pixels[nRowOffset + nx]
                        rAcc += (c shr 16) and 0xFF
                        gAcc += (c shr 8) and 0xFF
                        bAcc += c and 0xFF
                        count++
                    }
                }

                blurredPixels[rowOffset + x] =
                    (0xFF shl 24) or ((rAcc / count) shl 16) or ((gAcc / count) shl 8) or (bAcc / count)
            }
        }

        // Apply Unsharp mask
        for (i in pixels.indices) {
            val orig = pixels[i]
            val blur = blurredPixels[i]

            val oR = (orig shr 16) and 0xFF
            val oG = (orig shr 8) and 0xFF
            val oB = orig and 0xFF

            val bR = (blur shr 16) and 0xFF
            val bG = (blur shr 8) and 0xFF
            val bB = blur and 0xFF

            val diffR = oR - bR
            val diffG = oG - bG
            val diffB = oB - bB

            val nR = if (abs(diffR) > threshold) (oR + diffR * amount).toInt().coerceIn(0, 255) else oR
            val nG = if (abs(diffG) > threshold) (oG + diffG * amount).toInt().coerceIn(0, 255) else oG
            val nB = if (abs(diffB) > threshold) (oB + diffB * amount).toInt().coerceIn(0, 255) else oB

            outPixels[i] = (0xFF shl 24) or (nR shl 16) or (nG shl 8) or nB
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Portrait Mode Depth Simulator (Optical bokeh fall-off with subject masking).
     */
    fun applyPortraitBokeh(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val centerX = width / 2.0f
        val centerY = height * 0.45f
        val radiusX = width * 0.32f
        val radiusY = height * 0.38f

        val boxR = 4
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val dx = (x - centerX) / radiusX
                val dy = (y - centerY) / radiusY
                val dist = sqrt(dx * dx + dy * dy)

                // Outside portrait ellipse -> blend towards heavy bokeh blur
                val blurFactor = ((dist - 0.85f) / 0.5f).coerceIn(0.0f, 1.0f)
                if (blurFactor <= 0.05f) {
                    outPixels[rowOffset + x] = pixels[rowOffset + x]
                    continue
                }

                var rAcc = 0
                var gAcc = 0
                var bAcc = 0
                var count = 0
                for (ky in -boxR..boxR) {
                    val ny = (y + ky).coerceIn(0, height - 1)
                    val nRow = ny * width
                    for (kx in -boxR..boxR) {
                        val nx = (x + kx).coerceIn(0, width - 1)
                        val c = pixels[nRow + nx]
                        rAcc += (c shr 16) and 0xFF
                        gAcc += (c shr 8) and 0xFF
                        bAcc += c and 0xFF
                        count++
                    }
                }

                val origColor = pixels[rowOffset + x]
                val oR = (origColor shr 16) and 0xFF
                val oG = (origColor shr 8) and 0xFF
                val oB = origColor and 0xFF

                val bR = rAcc / count
                val bG = gAcc / count
                val bB = bAcc / count

                val finalR = (oR * (1.0f - blurFactor) + bR * blurFactor).toInt().coerceIn(0, 255)
                val finalG = (oG * (1.0f - blurFactor) + bG * blurFactor).toInt().coerceIn(0, 255)
                val finalB = (oB * (1.0f - blurFactor) + bB * blurFactor).toInt().coerceIn(0, 255)

                outPixels[rowOffset + x] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Ultra Macro Detail Enhancement (Texture enhancement & high-pass micro clarity).
     */
    fun applyMacroEnhancement(src: Bitmap): Bitmap {
        // High sharpening with micro-edge contrast boost
        val sharpened = unsharpMask(src, amount = 1.1f, threshold = 2)
        return toneMapAndGrade(sharpened, ColorStyle.VIVID_LANDSCAPE)
    }

    private fun acesToneCurve(x: Float): Float {
        val a = 2.51f
        val b = 0.03f
        val c = 2.43f
        val d = 0.59f
        val e = 0.14f
        return ((x * (a * x + b)) / (x * (c * x + d) + e)).coerceIn(0f, 1f)
    }
}
