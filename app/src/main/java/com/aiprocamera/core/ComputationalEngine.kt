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
     * ponytail: Single-scale spatial blur on weights replaces 5-tier Laplacian pyramid.
     * Upgrade path: RenderScript-toolkit or Vulkan compute shader when 60fps video needed.
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
                    val r = (color shr 16 and 0xFF) / 255.0f
                    val g = (color shr 8 and 0xFF) / 255.0f
                    val b = (color and 0xFF) / 255.0f

                    // 1. Well-exposedness: Gaussian centered at 0.5 (sigma = 0.2)
                    val sigmaSq = 2.0f * 0.2f * 0.2f
                    val wExpR = exp(-((r - 0.5f) * (r - 0.5f)) / sigmaSq)
                    val wExpG = exp(-((g - 0.5f) * (g - 0.5f)) / sigmaSq)
                    val wExpB = exp(-((b - 0.5f) * (b - 0.5f)) / sigmaSq)
                    val wellExposedness = wExpR * wExpG * wExpB

                    // 2. Saturation: standard deviation across RGB
                    val mean = (r + g + b) / 3.0f
                    val sat = sqrt(((r - mean) * (r - mean) + (g - mean) * (g - mean) + (b - mean) * (b - mean)) / 3.0f)

                    // 3. Contrast: local gradient magnitude
                    val leftIdx = if (x > 0) idx - 1 else idx
                    val rightIdx = if (x < width - 1) idx + 1 else idx
                    val topIdx = if (y > 0) idx - width else idx
                    val bottomIdx = if (y < height - 1) idx + width else idx

                    val lumCenter = 0.299f * r + 0.587f * g + 0.114f * b
                    val lumL = getLuminance(pixels[leftIdx])
                    val lumR = getLuminance(pixels[rightIdx])
                    val lumT = getLuminance(pixels[topIdx])
                    val lumB = getLuminance(pixels[bottomIdx])

                    val contrast = abs(lumR - lumL) + abs(lumB - lumT)

                    // Combined Mertens weight (epsilon 1e-4 prevents divide by zero)
                    weights[idx] = (contrast * 1.5f + sat * 1.0f + wellExposedness * 2.0f).coerceAtLeast(0.0001f)
                }
            }
            // Spatial smooth weights to prevent boundary seam halos
            fastBoxBlur(weights, width, height, radius = 2)
        }

        // Normalize weights & blend
        val outputPixels = IntArray(numPixels)
        for (i in 0 until numPixels) {
            var sumW = 0.0f
            for (f in 0 until numFrames) {
                sumW += weightMaps[f][i]
            }

            var fusedR = 0.0f
            var fusedG = 0.0f
            var fusedB = 0.0f

            for (f in 0 until numFrames) {
                val normW = weightMaps[f][i] / sumW
                val color = pixelBuffers[f][i]
                fusedR += (color shr 16 and 0xFF).toFloat() * normW
                fusedG += (color shr 8 and 0xFF).toFloat() * normW
                fusedB += (color and 0xFF).toFloat() * normW
            }

            val r = fusedR.toInt().coerceIn(0, 255)
            val g = fusedG.toInt().coerceIn(0, 255)
            val b = fusedB.toInt().coerceIn(0, 255)
            outputPixels[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }

        val outBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return outBmp
    }

    /**
     * Edge-preserving Bilateral Denoising on Luminance:
     * Removes sensor grain in dark shadows while keeping mountain edges razor-sharp.
     */
    fun bilateralDenoise(src: Bitmap, spatialSigma: Float = 2.0f, rangeSigma: Float = 25.0f): Bitmap {
        val width = src.width
        val height = src.height
        val numPixels = width * height
        val pixels = IntArray(numPixels)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val output = IntArray(numPixels)
        val radius = 2
        val twoSigmaSpatialSq = 2.0f * spatialSigma * spatialSigma
        val twoSigmaRangeSq = 2.0f * rangeSigma * rangeSigma

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val centerIdx = rowOffset + x
                val centerColor = pixels[centerIdx]
                val cR = (centerColor shr 16 and 0xFF)
                val cG = (centerColor shr 8 and 0xFF)
                val cB = (centerColor and 0xFF)
                val cLum = 0.299f * cR + 0.587f * cG + 0.114f * cB

                var sumWeights = 0.0f
                var sumR = 0.0f
                var sumG = 0.0f
                var sumB = 0.0f

                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, height - 1)
                    val nRow = ny * width
                    for (dx in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, width - 1)
                        val neighborColor = pixels[nRow + nx]
                        val nR = (neighborColor shr 16 and 0xFF)
                        val nG = (neighborColor shr 8 and 0xFF)
                        val nB = (neighborColor and 0xFF)
                        val nLum = 0.299f * nR + 0.587f * nG + 0.114f * nB

                        val distSq = (dx * dx + dy * dy).toFloat()
                        val lumDiff = cLum - nLum
                        val weight = exp(-(distSq / twoSigmaSpatialSq) - ((lumDiff * lumDiff) / twoSigmaRangeSq))

                        sumWeights += weight
                        sumR += nR.toFloat() * weight
                        sumG += nG.toFloat() * weight
                        sumB += nB.toFloat() * weight
                    }
                }

                val outR = (sumR / sumWeights).toInt().coerceIn(0, 255)
                val outG = (sumG / sumWeights).toInt().coerceIn(0, 255)
                val outB = (sumB / sumWeights).toInt().coerceIn(0, 255)
                output[centerIdx] = -0x1000000 or (outR shl 16) or (outG shl 8) or outB
            }
        }

        val outBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(output, 0, width, 0, 0, width, height)
        return outBmp
    }

    /**
     * Filmic Tone Mapping + S-Curve Dynamic Range Lift:
     * Lifts dark Himalayan valleys/villages without washing out sky or blowing snow peaks.
     */
    fun toneMapAndGrade(src: Bitmap, style: ColorStyle): Bitmap {
        val width = src.width
        val height = src.height
        val numPixels = width * height
        val pixels = IntArray(numPixels)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val outPixels = IntArray(numPixels)

        for (i in 0 until numPixels) {
            val c = pixels[i]
            var r = (c shr 16 and 0xFF) / 255.0f
            var g = (c shr 8 and 0xFF) / 255.0f
            var b = (c and 0xFF) / 255.0f

            // 1. Adaptive Shadow Lift: boosts values under 0.4
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val shadowFactor = (1.0f - lum).coerceIn(0.0f, 1.0f)
            val lift = shadowFactor * shadowFactor * 0.22f // +22% lift in blackest shadows

            r = (r + lift).coerceIn(0.0f, 1.0f)
            g = (g + lift).coerceIn(0.0f, 1.0f)
            b = (b + lift).coerceIn(0.0f, 1.0f)

            // 2. Film S-curve tone reproduction (3x^2 - 2x^3 hermite smoothstep)
            r = r * r * (3.0f - 2.0f * r)
            g = g * g * (3.0f - 2.0f * g)
            b = b * b * (3.0f - 2.0f * b)

            // 3. Pro Color Grading
            when (style) {
                ColorStyle.NEPAL_TWILIGHT -> {
                    // Deep cobalt blue hour sky + golden alpenglow highlights
                    if (lum > 0.6f && r > b) {
                        // Peak snow alpenglow: warm pink/gold pop
                        r = (r * 1.08f).coerceAtMost(1.0f)
                        b = (b * 0.95f)
                    } else if (lum in 0.25f..0.65f) {
                        // Twilight sky: boost sapphire blue
                        b = (b * 1.10f).coerceAtMost(1.0f)
                        g = (g * 1.02f).coerceAtMost(1.0f)
                    } else if (lum < 0.25f) {
                        // Recovered foliage in valley: rich natural green
                        g = (g * 1.07f).coerceAtMost(1.0f)
                    }
                }
                ColorStyle.LEICA_NATURAL -> {
                    // Subtly desaturate harsh digital clipping, boost natural contrast
                    val midtones = 0.5f + (lum - 0.5f) * 1.15f
                    r = (r * 0.98f + midtones * 0.02f).coerceIn(0f, 1f)
                    g = (g * 0.99f + midtones * 0.01f).coerceIn(0f, 1f)
                }
                ColorStyle.VIVID_LANDSCAPE -> {
                    // Boost vibrance on low-saturated pixels
                    val maxCh = max(r, max(g, b))
                    val minCh = min(r, min(g, b))
                    val sat = maxCh - minCh
                    val boost = (1.0f - sat) * 0.18f
                    r = (r + (r - lum) * boost).coerceIn(0f, 1f)
                    g = (g + (g - lum) * boost).coerceIn(0f, 1f)
                    b = (b + (b - lum) * boost).coerceIn(0f, 1f)
                }
            }

            outPixels[i] = -0x1000000 or
                    ((r * 255.0f).toInt().coerceIn(0, 255) shl 16) or
                    ((g * 255.0f).toInt().coerceIn(0, 255) shl 8) or
                    ((b * 255.0f).toInt().coerceIn(0, 255))
        }

        val outBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(outPixels, 0, width, 0, 0, width, height)
        return outBmp
    }

    /**
     * Unsharp Masking for Mountain Texture & Architecture Crispness:
     * Threshold-gated so noise isn't sharpened, only true structure edges.
     */
    fun unsharpMask(src: Bitmap, amount: Float = 0.65f, threshold: Int = 4): Bitmap {
        val width = src.width
        val height = src.height
        val numPixels = width * height
        val srcPixels = IntArray(numPixels)
        src.getPixels(srcPixels, 0, width, 0, 0, width, height)

        // Generate fast 3x3 box blur base
        val blurred = IntArray(numPixels)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var rAcc = 0
                var gAcc = 0
                var bAcc = 0
                var count = 0

                for (dy in -1..1) {
                    val py = (y + dy).coerceIn(0, height - 1)
                    val pRow = py * width
                    for (dx in -1..1) {
                        val px = (x + dx).coerceIn(0, width - 1)
                        val c = srcPixels[pRow + px]
                        rAcc += (c shr 16 and 0xFF)
                        gAcc += (c shr 8 and 0xFF)
                        bAcc += (c and 0xFF)
                        count++
                    }
                }
                blurred[row + x] = -0x1000000 or ((rAcc / count) shl 16) or ((gAcc / count) shl 8) or (bAcc / count)
            }
        }

        // Apply high-pass difference if above threshold
        val outPixels = IntArray(numPixels)
        for (i in 0 until numPixels) {
            val orig = srcPixels[i]
            val blur = blurred[i]

            val oR = orig shr 16 and 0xFF
            val oG = orig shr 8 and 0xFF
            val oB = orig and 0xFF

            val bR = blur shr 16 and 0xFF
            val bG = blur shr 8 and 0xFF
            val bB = blur and 0xFF

            val diffR = oR - bR
            val diffG = oG - bG
            val diffB = oB - bB

            val sR = if (abs(diffR) >= threshold) (oR.toFloat() + diffR.toFloat() * amount).toInt().coerceIn(0, 255) else oR
            val sG = if (abs(diffG) >= threshold) (oG.toFloat() + diffG.toFloat() * amount).toInt().coerceIn(0, 255) else oG
            val sB = if (abs(diffB) >= threshold) (oB.toFloat() + diffB.toFloat() * amount).toInt().coerceIn(0, 255) else oB

            outPixels[i] = -0x1000000 or (sR shl 16) or (sG shl 8) or sB
        }

        val outBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(outPixels, 0, width, 0, 0, width, height)
        return outBmp
    }

    private fun getLuminance(color: Int): Float {
        val r = (color shr 16 and 0xFF) / 255.0f
        val g = (color shr 8 and 0xFF) / 255.0f
        val b = (color and 0xFF) / 255.0f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun fastBoxBlur(map: FloatArray, w: Int, h: Int, radius: Int) {
        val temp = FloatArray(map.size)
        // Horizontal pass
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sum = 0.0f
                var count = 0
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx in 0 until w) {
                        sum += map[row + nx]
                        count++
                    }
                }
                temp[row + x] = sum / count
            }
        }
        // Vertical pass
        for (x in 0 until w) {
            for (y in 0 until h) {
                var sum = 0.0f
                var count = 0
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny in 0 until h) {
                        sum += temp[ny * w + x]
                        count++
                    }
                }
                map[y * w + x] = sum / count
            }
        }
    }
}
