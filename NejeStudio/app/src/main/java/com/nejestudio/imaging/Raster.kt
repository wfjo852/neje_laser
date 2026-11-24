package com.nejestudio.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import com.nejestudio.model.CanvasItem
import com.nejestudio.model.FontKind
import com.nejestudio.model.RenderMode
import com.nejestudio.model.TextSpec
import kotlin.math.ceil
import kotlin.math.max

/** 흑백(1비트) 변환. 결과 마스크에서 true = 각인 */
object Raster {

    /** 지정 크기로 축소/확대한 뒤 밝기(0..255) 배열. 투명 영역은 흰색으로 취급 */
    fun gray(src: Bitmap, w: Int, h: Int): IntArray {
        val scaled = if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== src) scaled.recycle()
        return IntArray(w * h) { i ->
            val c = px[i]
            val a = c ushr 24
            val l = (((c shr 16) and 255) * 30 + ((c shr 8) and 255) * 59 + (c and 255) * 11) / 100
            255 - a * (255 - l) / 255
        }
    }

    fun threshold(g: IntArray, t: Int) = BooleanArray(g.size) { g[it] < t }

    /** Floyd–Steinberg 오차 확산 */
    fun dither(g: IntArray, w: Int, h: Int, t: Int): BooleanArray {
        val e = FloatArray(g.size) { g[it].toFloat() }
        val out = BooleanArray(g.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = e[i]
                val black = old < t
                out[i] = black
                val err = old - if (black) 0f else 255f
                if (x + 1 < w) e[i + 1] += err * 7 / 16
                if (y + 1 < h) {
                    if (x > 0) e[i + w - 1] += err * 3 / 16
                    e[i + w] += err * 5 / 16
                    if (x + 1 < w) e[i + w + 1] += err * 1 / 16
                }
            }
        }
        return out
    }

    /** 이진화한 모양의 테두리만 남김. 두께는 모양 안쪽으로 두꺼워진다 */
    fun outline(g: IntArray, w: Int, h: Int, t: Int, thickness: Int): BooleanArray {
        val m = threshold(g, t)
        var edge = BooleanArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!m[i]) continue
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1 ||
                !m[i - 1] || !m[i + 1] || !m[i - w] || !m[i + w]
            ) edge[i] = true
        }
        repeat(thickness - 1) {
            val next = edge.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (edge[i] || !m[i]) continue
                if ((x > 0 && edge[i - 1]) || (x < w - 1 && edge[i + 1]) ||
                    (y > 0 && edge[i - w]) || (y < h - 1 && edge[i + w])
                ) next[i] = true
            }
            edge = next
        }
        return edge
    }

    fun render(item: CanvasItem, w: Int, h: Int, thicknessScale: Float = 1f): BooleanArray {
        var g = gray(item.source, w, h)
        if (item.invert) g = IntArray(g.size) { 255 - g[it] }
        return when (item.mode) {
            RenderMode.THRESHOLD -> threshold(g, item.threshold)
            RenderMode.DITHER -> dither(g, w, h, item.threshold)
            RenderMode.OUTLINE -> outline(g, w, h, item.threshold,
                max(1, (item.thickness * thicknessScale).toInt()))
        }
    }

    fun maskToBitmap(mask: BooleanArray, w: Int, h: Int, color: Int = Color.BLACK): Bitmap {
        val px = IntArray(w * h) { if (mask[it]) color else Color.TRANSPARENT }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun rotate90(src: Bitmap): Bitmap =
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(90f) }, true)
}

object TextRenderer {
    /** 렌더링 기준 글자 크기(px). 결과 비트맵은 이 크기로 그려진 뒤 잉크 영역만 남긴다 */
    const val BASE_SIZE = 200f

    fun render(spec: TextSpec): Bitmap {
        val base = when (spec.font) {
            FontKind.SANS -> Typeface.SANS_SERIF
            FontKind.SERIF -> Typeface.SERIF
            FontKind.MONO -> Typeface.MONOSPACE
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = BASE_SIZE
            color = Color.BLACK
            typeface = Typeface.create(base, if (spec.bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val text = spec.text.ifEmpty { " " }
        val width = text.split('\n').maxOf { ceil(paint.measureText(it)).toInt() }.coerceAtLeast(1) + 8
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(true)
            .build()
        val bmp = Bitmap.createBitmap(width, layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bmp))
        return trim(bmp)
    }

    /** 투명 여백 제거 */
    private fun trim(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 16) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return b
        val pad = 2
        minX = max(0, minX - pad); minY = max(0, minY - pad)
        maxX = minOf(w - 1, maxX + pad); maxY = minOf(h - 1, maxY + pad)
        val out = Bitmap.createBitmap(b, minX, minY, maxX - minX + 1, maxY - minY + 1)
        if (out !== b) b.recycle()
        return out
    }
}

object BitmapLoader {
    /** 사진을 불러와 긴 변이 [maxSide] 이하가 되도록 줄이고 EXIF 회전을 적용 */
    fun load(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val deg = cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (deg != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        }
        val longSide = max(bmp.width, bmp.height)
        if (longSide > maxSide) {
            val s = maxSide.toFloat() / longSide
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        }
        bmp
    }.getOrNull()
}
