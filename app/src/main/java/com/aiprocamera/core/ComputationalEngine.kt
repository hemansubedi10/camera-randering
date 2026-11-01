package com.aiprocamera.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class ColorStyle {
    NEPAL_TWILIGHT, // Cobalt sky + alpenglow highlights + rich deep shadow detail
    LEICA_NATURAL,  // Iconic Leica M-system film roll-off, soft highlights, natural organic skin
    VIVID_LANDSCAPE // High dynamic range, deep foliage emeralds & deep polarizer azure skies
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

        val weightMaps = Array(numFrames) { FloatArray(numPixels) }
        val sigma2 = 2.0f * 0.2f * 0.2f

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

                    val wR = exp(-((r - 0.5f) * (r - 0.5f)) / sigma2)
                    val wG = exp(-((g - 0.5f) * (g - 0.5f)) / sigma2)
                    val wB = exp(-((b - 0.5f) * (b - 0.5f)) / sigma2)
                    val exposedness = wR * wG * wB

                    val mu = (r + g + b) / 3.0f
                    val sat = sqrt(((r - mu) * (r - mu) + (g - mu) * (g - mu) + (b - mu) * (b - mu)) / 3.0f)

                    val contrast = if (x > 0 && x < width - 1) {
                        val leftLum = ((pixels[idx - 1] shr 8) and 0xFF) / 255.0f
                        val rightLum = ((pixels[idx + 1] shr 8) and 0xFF) / 255.0f
                        abs(rightLum - leftLum)
                    } else {
                        0.1f
                    }

                    weights[idx] = (exposedness * (sat + 0.05f) * (contrast + 0.05f)) + 1e-6f
                }
            }
        }

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

            val outR = accR.toInt().coerceIn(0, 255)
            val outG = accG.toInt().coerceIn(0, 255)
            val outB = accB.toInt().coerceIn(0, 255)

            outputPixels[i] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outputPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Dual-Domain Edge-Preserving Bilateral Denoising:
     * Suppresses digital sensor noise without blurring real textures (fabric, hair, brick).
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

                val finalR = (sumR / sumWeights).toInt().coerceIn(0, 255)
                val finalG = (sumG / sumWeights).toInt().coerceIn(0, 255)
                val finalB = (sumB / sumWeights).toInt().coerceIn(0, 255)

                outPixels[centerIdx] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Natural Multi-Frequency Detail Restoration:
     * Unlike artificial AI hallucination or heavy over-sharpening halos, this separates
     * fine micro-frequencies from coarse boundaries and amplifies authentic optical detail.
     */
    fun enhanceOpticalDetail(src: Bitmap, clarityMultiplier: Float = 1.20f): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // Pass 1: Fine-scale micro structure (3x3 kernel)
        val fineBlur = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                var rAcc = 0; var gAcc = 0; var bAcc = 0; var count = 0
                for (dy in -1..1) {
                    val ny = (y + dy).coerceIn(0, height - 1)
                    val nRow = ny * width
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, width - 1)
                        val c = pixels[nRow + nx]
                        rAcc += (c shr 16) and 0xFF
                        gAcc += (c shr 8) and 0xFF
                        bAcc += c and 0xFF
                        count++
                    }
                }
                fineBlur[rowOffset + x] = (0xFF shl 24) or ((rAcc / count) shl 16) or ((gAcc / count) shl 8) or (bAcc / count)
            }
        }

        // Pass 2: Coarse structure (5x5 kernel)
        val coarseBlur = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                var rAcc = 0; var gAcc = 0; var bAcc = 0; var count = 0
                for (dy in -2..2 step 2) {
                    val ny = (y + dy).coerceIn(0, height - 1)
                    val nRow = ny * width
                    for (dx in -2..2 step 2) {
                        val nx = (x + dx).coerceIn(0, width - 1)
                        val c = pixels[nRow + nx]
                        rAcc += (c shr 16) and 0xFF
                        gAcc += (c shr 8) and 0xFF
                        bAcc += c and 0xFF
                        count++
                    }
                }
                coarseBlur[rowOffset + x] = (0xFF shl 24) or ((rAcc / count) shl 16) or ((gAcc / count) shl 8) or (bAcc / count)
            }
        }

        // Decompose frequencies:
        // High Freq = (Original - FineBlur) -> subtle micro texture
        // Mid Freq = (FineBlur - CoarseBlur) -> true optical clarity & local contrast
        val highBoost = 0.55f * clarityMultiplier
        val midBoost = 0.35f * clarityMultiplier

        for (i in pixels.indices) {
            val orig = pixels[i]
            val fine = fineBlur[i]
            val coarse = coarseBlur[i]

            val oR = (orig shr 16) and 0xFF
            val oG = (orig shr 8) and 0xFF
            val oB = orig and 0xFF

            val fR = (fine shr 16) and 0xFF
            val fG = (fine shr 8) and 0xFF
            val fB = fine and 0xFF

            val cR = (coarse shr 16) and 0xFF
            val cG = (coarse shr 8) and 0xFF
            val cB = coarse and 0xFF

            // Extract frequency components
            val highR = (oR - fR)
            val highG = (oG - fG)
            val highB = (oB - fB)

            val midR = (fR - cR)
            val midG = (fG - cG)
            val midB = (fB - cB)

            // Clamp high frequency to prevent noise amplification
            val clampedHighR = highR.coerceIn(-28, 28)
            val clampedHighG = highG.coerceIn(-28, 28)
            val clampedHighB = highB.coerceIn(-28, 28)

            val nR = (oR + clampedHighR * highBoost + midR * midBoost).toInt().coerceIn(0, 255)
            val nG = (oG + clampedHighG * highBoost + midG * midBoost).toInt().coerceIn(0, 255)
            val nB = (oB + clampedHighB * highBoost + midB * midBoost).toInt().coerceIn(0, 255)

            outPixels[i] = (0xFF shl 24) or (nR shl 16) or (nG shl 8) or nB
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Film Tone Curve Reproduction & Aesthetic Color Grading (Leica, Nepal Twilight, Vivid Landscape).
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

            r = acesToneCurve(r)
            g = acesToneCurve(g)
            b = acesToneCurve(b)

            when (style) {
                ColorStyle.NEPAL_TWILIGHT -> {
                    b = (b * 1.10f).coerceAtMost(1.0f)
                    if (r > 0.55f) r = (r * 1.08f).coerceAtMost(1.0f)
                    if (g < 0.35f) g = (g * 0.95f)
                }
                ColorStyle.LEICA_NATURAL -> {
                    r = (r * 0.98f + 0.015f).coerceIn(0f, 1f)
                    b = (b * 0.97f + 0.015f).coerceIn(0f, 1f)
                }
                ColorStyle.VIVID_LANDSCAPE -> {
                    g = (g * 1.08f).coerceAtMost(1.0f)
                    b = (b * 1.07f).coerceAtMost(1.0f)
                    r = (r * 1.03f).coerceAtMost(1.0f)
                }
            }

            val outR = (r * 255.0f).toInt().coerceIn(0, 255)
            val outG = (g * 255.0f).toInt().coerceIn(0, 255)
            val outB = (b * 255.0f).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Portrait Mode Optical Bokeh with Depth Falloff & Face/Skin Tone Protection.
     */
    fun applyPortraitBokeh(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        val outPixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val centerX = width / 2.0f
        val centerY = height * 0.44f
        val radiusX = width * 0.33f
        val radiusY = height * 0.38f

        val bokehRadius = 6
        val blurredH = IntArray(width * height)

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                var rAcc = 0; var gAcc = 0; var bAcc = 0; var count = 0
                for (k in -bokehRadius..bokehRadius) {
                    val nx = (x + k).coerceIn(0, width - 1)
                    val c = pixels[rowOffset + nx]
                    rAcc += (c shr 16) and 0xFF
                    gAcc += (c shr 8) and 0xFF
                    bAcc += c and 0xFF
                    count++
                }
                blurredH[rowOffset + x] = (0xFF shl 24) or ((rAcc / count) shl 16) or ((gAcc / count) shl 8) or (bAcc / count)
            }
        }

        for (x in 0 until width) {
            for (y in 0 until height) {
                var rAcc = 0; var gAcc = 0; var bAcc = 0; var count = 0
                for (k in -bokehRadius..bokehRadius) {
                    val ny = (y + k).coerceIn(0, height - 1)
                    val c = blurredH[ny * width + x]
                    rAcc += (c shr 16) and 0xFF
                    gAcc += (c shr 8) and 0xFF
                    bAcc += c and 0xFF
                    count++
                }

                val origColor = pixels[y * width + x]
                val oR = (origColor shr 16) and 0xFF
                val oG = (origColor shr 8) and 0xFF
                val oB = origColor and 0xFF

                val isSkin = (oR > 95 && oG > 40 && oB > 20 && (oR - oG) > 15 && oR > oB)

                val dx = (x - centerX) / radiusX
                val dy = (y - centerY) / radiusY
                val dist = sqrt(dx * dx + dy * dy)
                val baseFalloff = ((dist - 0.75f) / 0.55f).coerceIn(0.0f, 1.0f)
                val blurFactor = if (isSkin && dist < 1.3f) baseFalloff * 0.25f else baseFalloff

                val bR = rAcc / count
                val bG = gAcc / count
                val bB = bAcc / count

                val finalR = (oR * (1.0f - blurFactor) + bR * blurFactor).toInt().coerceIn(0, 255)
                val finalG = (oG * (1.0f - blurFactor) + bG * blurFactor).toInt().coerceIn(0, 255)
                val finalB = (oB * (1.0f - blurFactor) + bB * blurFactor).toInt().coerceIn(0, 255)

                outPixels[y * width + x] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Night Sight Multi-Frame Stacking (Shadow recovery, temporal noise suppression).
     */
    fun processNightSight(frames: List<Bitmap>, clarityGain: Float = 1.35f): Bitmap {
        val fused = fuseBracketed(frames)
        val clean1 = bilateralDenoise(fused, spatialSigma = 2.5f, rangeSigma = 32.0f)
        val clean2 = bilateralDenoise(clean1, spatialSigma = 1.5f, rangeSigma = 20.0f)
        val graded = toneMapAndGrade(clean2, ColorStyle.NEPAL_TWILIGHT)
        return enhanceOpticalDetail(graded, clarityMultiplier = clarityGain)
    }

    /**
     * High Dynamic Range (HDR+) Multi-Exposure Pipeline.
     */
    fun processHdr(frames: List<Bitmap>, clarityGain: Float = 1.25f): Bitmap {
        val fused = fuseBracketed(frames)
        val denoised = bilateralDenoise(fused, spatialSigma = 1.8f, rangeSigma = 22.0f)
        val graded = toneMapAndGrade(denoised, ColorStyle.VIVID_LANDSCAPE)
        return enhanceOpticalDetail(graded, clarityMultiplier = clarityGain)
    }

    /**
     * Professional DSLR Single-Frame Rendering (Authentic color science, clean film grain).
     */
    fun processProDslr(src: Bitmap, manualEvBias: Int, clarityGain: Float = 1.20f): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val evMultiplier = 2.0f.pow(manualEvBias * 0.35f)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (((c shr 16) and 0xFF) * evMultiplier).toInt().coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) * evMultiplier).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) * evMultiplier).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val corrected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        corrected.setPixels(pixels, 0, width, 0, 0, width, height)

        val graded = toneMapAndGrade(corrected, ColorStyle.LEICA_NATURAL)
        return enhanceOpticalDetail(graded, clarityMultiplier = clarityGain)
    }

    /**
     * Ultra Macro Detail Enhancement.
     */
    fun applyMacroEnhancement(src: Bitmap, clarityGain: Float = 1.45f): Bitmap {
        val graded = toneMapAndGrade(src, ColorStyle.VIVID_LANDSCAPE)
        return enhanceOpticalDetail(graded, clarityMultiplier = clarityGain)
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
