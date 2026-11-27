package com.zeroconvert.ime.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class CropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

data class SuggestedPhotoPalette(
    val isDark: Boolean,
    val dimColor: Long,
    val dimAlpha: Float,
    val normalKeyFill: Long,
    val modifierKeyFill: Long,
    val accentKeyFill: Long,
    val accentTextColor: Long,
    val primaryTextColor: Long,
    val secondaryTextColor: Long,
    val borderColor: Long,
    val barBgColor: Long
)

object ThemeContrastChecker {
    fun compositeOver(fg: Long, alpha: Float, bg: Long): Long {
        val a = alpha.coerceIn(0f, 1f)
        val fr = ((fg shr 16) and 0xFF).toFloat()
        val fgG = ((fg shr 8) and 0xFF).toFloat()
        val fb = (fg and 0xFF).toFloat()
        val br = ((bg shr 16) and 0xFF).toFloat()
        val bgG = ((bg shr 8) and 0xFF).toFloat()
        val bb = (bg and 0xFF).toFloat()
        val r = (fr * a + br * (1f - a)).roundToInt().coerceIn(0, 255).toLong()
        val g = (fgG * a + bgG * (1f - a)).roundToInt().coerceIn(0, 255).toLong()
        val b = (fb * a + bb * (1f - a)).roundToInt().coerceIn(0, 255).toLong()
        return (0xFFL shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun channel(byteVal: Long): Double {
        val v = byteVal.toDouble() / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Long): Double =
        0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)

    fun contrastRatio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun effectiveKeyContrast(spec: KeyboardThemeSpec, sampledBgColor: Long? = null): Double {
        val baseBg = when (spec.background.kind) {
            BackgroundKind.SOLID -> spec.background.solidColor
            BackgroundKind.LINEAR_GRADIENT -> compositeOver(
                spec.background.gradientStartColor,
                0.5f,
                spec.background.gradientEndColor
            )
            BackgroundKind.IMAGE -> compositeOver(
                spec.background.dimColor,
                spec.background.dimAlpha,
                sampledBgColor ?: spec.background.solidColor
            )
        }
        val keyBg = compositeOver(
            spec.keySurface.normalKeyFill,
            spec.keySurface.keyFillAlpha,
            baseBg
        )
        return contrastRatio(spec.typography.primaryTextColor, keyBg)
    }
}

object ThemeBackgroundProcessor {

    fun computeCropRect(
        srcWidth: Int,
        srcHeight: Int,
        targetAspectRatio: Float,
        cropCenterX: Float,
        cropCenterY: Float,
        cropScale: Float
    ): CropRect {
        val safeW = srcWidth.coerceAtLeast(1)
        val safeH = srcHeight.coerceAtLeast(1)
        val safeAspect = targetAspectRatio.coerceIn(0.25f, 4.0f)
        val safeScale = cropScale.coerceIn(1.0f, 3.0f)
        val cx = cropCenterX.coerceIn(0f, 1f)
        val cy = cropCenterY.coerceIn(0f, 1f)

        val srcAspect = safeW.toFloat() / safeH.toFloat()
        val baseW: Float
        val baseH: Float
        if (srcAspect > safeAspect) {
            baseH = safeH.toFloat()
            baseW = baseH * safeAspect
        } else {
            baseW = safeW.toFloat()
            baseH = baseW / safeAspect
        }

        val cropW = (baseW / safeScale).roundToInt().coerceIn(1, safeW)
        val cropH = (baseH / safeScale).roundToInt().coerceIn(1, safeH)
        val maxLeft = (safeW - cropW).coerceAtLeast(0)
        val maxTop = (safeH - cropH).coerceAtLeast(0)
        val left = ((safeW * cx) - cropW / 2f).roundToInt().coerceIn(0, maxLeft)
        val top = ((safeH * cy) - cropH / 2f).roundToInt().coerceIn(0, maxTop)
        return CropRect(left = left, top = top, width = cropW, height = cropH)
    }

    fun computeCenterCropSourceRect(
        imgWidth: Int,
        imgHeight: Int,
        dstWidth: Int,
        dstHeight: Int
    ): CropRect {
        val safeImgW = imgWidth.coerceAtLeast(1)
        val safeImgH = imgHeight.coerceAtLeast(1)
        val safeDstW = dstWidth.coerceAtLeast(1)
        val safeDstH = dstHeight.coerceAtLeast(1)
        val targetAspect = safeDstW.toFloat() / safeDstH.toFloat()
        return computeCropRect(
            srcWidth = safeImgW,
            srcHeight = safeImgH,
            targetAspectRatio = targetAspect,
            cropCenterX = 0.5f,
            cropCenterY = 0.5f,
            cropScale = 1.0f
        )
    }

    /**
     * Pure-Kotlin integer StackBlur operating in-place on [pixels] (0xAARRGGBB).
     * Runs safely on API 26+ and inside JVM unit tests with zero native or RenderScript dependencies.
     */
    fun stackBlurPixels(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (radius < 1 || width <= 1 || height <= 1 || pixels.size < width * height) return

        val wm = width - 1
        val hm = height - 1
        val wh = width * height
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        val vmin = IntArray(max(width, height))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (i in dv.indices) {
            dv[i] = i / divsum
        }

        val stack = Array(div) { IntArray(3) }
        val r1 = radius + 1

        var yw = 0
        var yi = 0
        for (y in 0 until height) {
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var rinsum = 0
            var ginsum = 0
            var binsum = 0

            for (i in -radius..radius) {
                val p = pixels[yi + min(wm, max(i, 0))]
                val sir = stack[i + radius]
                sir[0] = (p and 0xFF0000) shr 16
                sir[1] = (p and 0x00FF00) shr 8
                sir[2] = p and 0x0000FF
                val rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            var stackpointer = radius
            for (x in 0 until width) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = min(x + radius + 1, wm)
                }
                val p = pixels[yw + vmin[x]]
                sir[0] = (p and 0xFF0000) shr 16
                sir[1] = (p and 0x00FF00) shr 8
                sir[2] = p and 0x0000FF

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                val nextSir = stack[stackpointer % div]
                routsum += nextSir[0]
                goutsum += nextSir[1]
                boutsum += nextSir[2]

                rinsum -= nextSir[0]
                ginsum -= nextSir[1]
                binsum -= nextSir[2]

                yi++
            }
            yw += width
        }

        for (x in 0 until width) {
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var rinsum = 0
            var ginsum = 0
            var binsum = 0
            var yp = -radius * width
            for (i in -radius..radius) {
                yi = max(0, yp) + x
                val sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                val rbs = r1 - abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) {
                    yp += width
                }
            }
            yi = x
            var stackpointer = radius
            for (y in 0 until height) {
                pixels[yi] = (0xFF shl 24) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackstart = stackpointer - radius + div
                val sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = min(y + r1, hm) * width
                }
                val p = x + vmin[y]
                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                val nextSir = stack[stackpointer]
                routsum += nextSir[0]
                goutsum += nextSir[1]
                boutsum += nextSir[2]

                rinsum -= nextSir[0]
                ginsum -= nextSir[1]
                binsum -= nextSir[2]

                yi += width
            }
        }
    }

    fun suggestPaletteFromPixels(pixels: IntArray): SuggestedPhotoPalette {
        if (pixels.isEmpty()) {
            return SuggestedPhotoPalette(
                isDark = true,
                dimColor = 0xFF000000L,
                dimAlpha = 0.30f,
                normalKeyFill = 0xFF141824L,
                modifierKeyFill = 0xFF0D111AL,
                accentKeyFill = 0xFF60A5FAL,
                accentTextColor = 0xFF08203EL,
                primaryTextColor = 0xFFF8FAFC,
                secondaryTextColor = 0xFFCBD5E1,
                borderColor = 0xFFFFFFFFL,
                barBgColor = 0xFF0F172AL
            )
        }
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var bestAccent = 0xFF60A5FAL
        var bestScore = -1f

        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            sumR += r
            sumG += g
            sumB += b
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val sat = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC.toFloat()
            val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            val score = sat * (1f - abs(lum - 0.55f))
            if (score > bestScore && sat > 0.22f) {
                bestScore = score
                bestAccent = (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
            }
        }

        val count = pixels.size.coerceAtLeast(1)
        val avgR = (sumR / count).toLong().coerceIn(0, 255)
        val avgG = (sumG / count).toLong().coerceIn(0, 255)
        val avgB = (sumB / count).toLong().coerceIn(0, 255)
        val avgColor = (0xFFL shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
        val avgLum = ThemeContrastChecker.luminance(avgColor)
        val isDark = avgLum < 0.45

        val tintDark = ThemeContrastChecker.compositeOver(avgColor, 0.25f, 0xFF0E131CL)
        val tintLight = ThemeContrastChecker.compositeOver(avgColor, 0.20f, 0xFFF8FAFC)

        val accentContrastWithWhite = ThemeContrastChecker.contrastRatio(0xFFFFFFFFL, bestAccent)
        val accentContrastWithBlack = ThemeContrastChecker.contrastRatio(0xFF0F172AL, bestAccent)
        val accent = if (maxOf(accentContrastWithWhite, accentContrastWithBlack) >= 4.5) {
            bestAccent
        } else if (isDark) {
            0xFF74B5FFL
        } else {
            0xFF1967D2L
        }
        val accentText = if (
            ThemeContrastChecker.contrastRatio(0xFFFFFFFFL, accent) >=
            ThemeContrastChecker.contrastRatio(0xFF0F172AL, accent)
        ) {
            0xFFFFFFFFL
        } else {
            0xFF0F172AL
        }

        return if (isDark) {
            SuggestedPhotoPalette(
                isDark = true,
                dimColor = 0xFF000000L,
                dimAlpha = 0.28f,
                normalKeyFill = tintDark,
                modifierKeyFill = ThemeContrastChecker.compositeOver(0xFF000000L, 0.30f, tintDark),
                accentKeyFill = accent,
                accentTextColor = accentText,
                primaryTextColor = 0xFFF8FAFC,
                secondaryTextColor = 0xFFE2E8F0,
                borderColor = 0xFFFFFFFFL,
                barBgColor = tintDark
            )
        } else {
            SuggestedPhotoPalette(
                isDark = false,
                dimColor = 0xFFFFFFFFL,
                dimAlpha = 0.22f,
                normalKeyFill = tintLight,
                modifierKeyFill = ThemeContrastChecker.compositeOver(0xFFCBD5E1L, 0.35f, tintLight),
                accentKeyFill = accent,
                accentTextColor = accentText,
                primaryTextColor = 0xFF0F172AL,
                secondaryTextColor = 0xFF334155L,
                borderColor = 0xFF0F172AL,
                barBgColor = tintLight
            )
        }
    }

    fun computeInSampleSize(srcWidth: Int, srcHeight: Int, maxSidePx: Int): Int {
        val safeW = srcWidth.coerceAtLeast(1)
        val safeH = srcHeight.coerceAtLeast(1)
        val safeMax = maxSidePx.coerceAtLeast(1)
        var inSampleSize = 1
        while ((safeW / inSampleSize) > safeMax * 2 || (safeH / inSampleSize) > safeMax * 2) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    fun computeTargetDimensions(srcWidth: Int, srcHeight: Int, maxSidePx: Int): Pair<Int, Int> {
        val safeW = srcWidth.coerceAtLeast(1)
        val safeH = srcHeight.coerceAtLeast(1)
        val safeMax = maxSidePx.coerceAtLeast(1)
        val maxDim = max(safeW, safeH)
        if (maxDim <= safeMax) return safeW to safeH
        val ratio = safeMax.toFloat() / maxDim.toFloat()
        val targetW = (safeW * ratio).roundToInt().coerceAtLeast(1)
        val targetH = (safeH * ratio).roundToInt().coerceAtLeast(1)
        return targetW to targetH
    }

    private fun ensureSoftwareArgb8888(bitmap: Bitmap): Bitmap? {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        bitmap.recycle()
        return copy
    }

    fun decodeAndNormalizeSource(context: Context, uri: Uri, maxSidePx: Int = 1440): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val imageDecoderResult = runCatching {
                val source = ImageDecoder.createSource(resolver, uri)
                val raw = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val (targetW, targetH) = computeTargetDimensions(
                        info.size.width,
                        info.size.height,
                        maxSidePx
                    )
                    decoder.setTargetSize(targetW, targetH)
                }
                ensureSoftwareArgb8888(raw)
            }.onFailure { err ->
                Log.w("ThemeBgProcessor", "ImageDecoder failed, falling back to BitmapFactory", err)
            }.getOrNull()
            if (imageDecoderResult != null) return imageDecoderResult
        }

        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = resolver.openInputStream(uri) ?: return null
            boundsStream.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSidePx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return null

            val orientation = runCatching {
                resolver.openInputStream(uri)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

            val oriented = applyExifOrientation(decoded, orientation)
            if (oriented !== decoded) decoded.recycle()

            val (targetW, targetH) = computeTargetDimensions(oriented.width, oriented.height, maxSidePx)
            val sized = if (targetW != oriented.width || targetH != oriented.height) {
                val scaled = Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
                if (scaled !== oriented) oriented.recycle()
                scaled
            } else {
                oriented
            }
            ensureSoftwareArgb8888(sized)
        }.onFailure { err ->
            Log.w("ThemeBgProcessor", "BitmapFactory decode failed for $uri", err)
        }.getOrNull()
    }

    fun decodeFileToBitmap(file: File, maxSidePx: Int = 1440): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            this.inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSidePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return ensureSoftwareArgb8888(decoded)
    }

    fun renderBakedBackground(
        source: Bitmap,
        cropCenterX: Float,
        cropCenterY: Float,
        cropScale: Float,
        blurRadiusDp: Float,
        targetWidthPx: Int = 1080,
        targetAspectRatio: Float = 1.65f
    ): Bitmap {
        val rect = computeCropRect(
            srcWidth = source.width,
            srcHeight = source.height,
            targetAspectRatio = targetAspectRatio,
            cropCenterX = cropCenterX,
            cropCenterY = cropCenterY,
            cropScale = cropScale
        )
        val cropped = Bitmap.createBitmap(source, rect.left, rect.top, rect.width, rect.height)
        val outW = min(targetWidthPx, rect.width).coerceAtLeast(1)
        val outH = (outW.toFloat() / targetAspectRatio).roundToInt().coerceAtLeast(1)
        val scaled = if (cropped.width != outW || cropped.height != outH) {
            Bitmap.createScaledBitmap(cropped, outW, outH, true).also {
                if (it !== cropped && cropped !== source) cropped.recycle()
            }
        } else {
            cropped
        }

        val clampedBlur = blurRadiusDp.coerceIn(0f, 24f)
        if (clampedBlur < 0.5f) {
            return if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.isMutable && scaled !== source) {
                scaled
            } else {
                scaled.copy(Bitmap.Config.ARGB_8888, true).also {
                    if (scaled !== source) scaled.recycle()
                }
            }
        }

        // Downscale by 2x for fast, smooth StackBlur and scale back up cleanly.
        val workW = (scaled.width / 2).coerceAtLeast(2)
        val workH = (scaled.height / 2).coerceAtLeast(2)
        val work = Bitmap.createScaledBitmap(scaled, workW, workH, true)
        if (work !== scaled && scaled !== source) scaled.recycle()
        val mutable = if (work.config == Bitmap.Config.ARGB_8888 && work.isMutable && work !== source) {
            work
        } else {
            work.copy(Bitmap.Config.ARGB_8888, true).also {
                if (work !== source) work.recycle()
            }
        }
        val pixels = IntArray(workW * workH)
        mutable.getPixels(pixels, 0, workW, 0, 0, workW, workH)
        val pxRadius = (clampedBlur * 1.15f).roundToInt().coerceIn(1, 30)
        stackBlurPixels(pixels, workW, workH, pxRadius)
        mutable.setPixels(pixels, 0, workW, 0, 0, workW, workH)
        return mutable
    }

    fun encodeBitmapToWebpBytes(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, quality.coerceIn(50, 100), out)
        return out.toByteArray()
    }

    fun suggestPaletteFromBitmap(bitmap: Bitmap): SuggestedPhotoPalette {
        val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val pixels = IntArray(24 * 24)
        sample.getPixels(pixels, 0, 24, 0, 0, 24, 24)
        if (sample !== bitmap) sample.recycle()
        return suggestPaletteFromPixels(pixels)
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

object ThemeBackgroundCache {
    private val cache = LruCache<String, ImageBitmap>(8)

    fun key(themeId: String, fileName: String, revision: Long): String =
        "$themeId|$fileName|$revision"

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun put(key: String, image: ImageBitmap) {
        cache.put(key, image)
    }

    fun loadRenderedImage(
        context: Context,
        themeId: String,
        fileName: String,
        revision: Long
    ): ImageBitmap? {
        val k = key(themeId, fileName, revision)
        cache.get(k)?.let { return it }
        val file = CustomThemeRepository.forContext(context).renderedImageFile(themeId, fileName)
        val bitmap = ThemeBackgroundProcessor.decodeFileToBitmap(file, maxSidePx = 1080) ?: return null
        val imageBitmap = bitmap.asImageBitmap()
        cache.put(k, imageBitmap)
        return imageBitmap
    }

    fun clear() {
        cache.evictAll()
    }
}
