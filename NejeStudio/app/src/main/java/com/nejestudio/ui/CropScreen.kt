package com.nejestudio.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nejestudio.imaging.Raster
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class CropHandle { NONE, MOVE, TL, TR, BL, BR }

private data class Fit(val scale: Float, val ox: Float, val oy: Float)

private fun fit(iw: Int, ih: Int, vw: Float, vh: Float, pad: Float): Fit {
    val s = min((vw - 2 * pad) / iw, (vh - 2 * pad) / ih)
    return Fit(s, (vw - iw * s) / 2, (vh - ih * s) / 2)
}

/** 이미지 잘라내기 (모서리 드래그로 크기, 안쪽 드래그로 이동) */
@Composable
fun CropScreen(bitmap: Bitmap, title: String, onCancel: () -> Unit, onDone: (Bitmap) -> Unit) {
    BackHandler(onBack = onCancel)
    var src by remember { mutableStateOf(bitmap) }
    val img = remember(src) { src.asImageBitmap() }
    var rect by remember(src) { mutableStateOf(Rect(0f, 0f, src.width.toFloat(), src.height.toFloat())) }

    Column(Modifier.fillMaxSize().background(Color(0xFF121212)).statusBarsPadding().navigationBarsPadding()) {
        Text(title, color = Color.White, modifier = Modifier.padding(16.dp))
        Canvas(
            Modifier.weight(1f).fillMaxWidth().pointerInput(src) {
                val pad = 24.dp.toPx()
                val grab = 32.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val f = fit(src.width, src.height, size.width.toFloat(), size.height.toFloat(), pad)
                    fun scr(x: Float, y: Float) = Offset(f.ox + x * f.scale, f.oy + y * f.scale)
                    val r = rect
                    val p = down.position
                    val handle = when {
                        (p - scr(r.left, r.top)).getDistance() < grab -> CropHandle.TL
                        (p - scr(r.right, r.top)).getDistance() < grab -> CropHandle.TR
                        (p - scr(r.left, r.bottom)).getDistance() < grab -> CropHandle.BL
                        (p - scr(r.right, r.bottom)).getDistance() < grab -> CropHandle.BR
                        Rect(scr(r.left, r.top), scr(r.right, r.bottom)).contains(p) -> CropHandle.MOVE
                        else -> CropHandle.NONE
                    }
                    if (handle == CropHandle.NONE) return@awaitEachGesture
                    val minSide = max(8f, min(src.width, src.height) * 0.02f)
                    val W = src.width.toFloat(); val H = src.height.toFloat()
                    drag(down.id) { ch ->
                        val d = ch.positionChange() / f.scale
                        ch.consume()
                        val c = rect
                        rect = when (handle) {
                            CropHandle.MOVE -> {
                                val dx = d.x.coerceIn(-c.left, W - c.right)
                                val dy = d.y.coerceIn(-c.top, H - c.bottom)
                                c.translate(dx, dy)
                            }
                            CropHandle.TL -> c.copy(left = (c.left + d.x).coerceIn(0f, c.right - minSide), top = (c.top + d.y).coerceIn(0f, c.bottom - minSide))
                            CropHandle.TR -> c.copy(right = (c.right + d.x).coerceIn(c.left + minSide, W), top = (c.top + d.y).coerceIn(0f, c.bottom - minSide))
                            CropHandle.BL -> c.copy(left = (c.left + d.x).coerceIn(0f, c.right - minSide), bottom = (c.bottom + d.y).coerceIn(c.top + minSide, H))
                            CropHandle.BR -> c.copy(right = (c.right + d.x).coerceIn(c.left + minSide, W), bottom = (c.bottom + d.y).coerceIn(c.top + minSide, H))
                            CropHandle.NONE -> c
                        }
                    }
                }
            },
        ) {
            val f = fit(src.width, src.height, size.width, size.height, 24.dp.toPx())
            drawImage(
                img, IntOffset.Zero, IntSize(img.width, img.height),
                IntOffset(f.ox.roundToInt(), f.oy.roundToInt()),
                IntSize((img.width * f.scale).roundToInt(), (img.height * f.scale).roundToInt()),
            )
            val tl = Offset(f.ox + rect.left * f.scale, f.oy + rect.top * f.scale)
            val br = Offset(f.ox + rect.right * f.scale, f.oy + rect.bottom * f.scale)
            val shade = Color(0x99000000)
            // 잘라낼 영역 바깥 어둡게
            drawRect(shade, Offset(0f, 0f), Size(size.width, tl.y))
            drawRect(shade, Offset(0f, br.y), Size(size.width, size.height - br.y))
            drawRect(shade, Offset(0f, tl.y), Size(tl.x, br.y - tl.y))
            drawRect(shade, Offset(br.x, tl.y), Size(size.width - br.x, br.y - tl.y))
            val w = br.x - tl.x; val h = br.y - tl.y
            for (i in 1..2) {
                drawLine(Color(0x66FFFFFF), Offset(tl.x + w * i / 3, tl.y), Offset(tl.x + w * i / 3, br.y))
                drawLine(Color(0x66FFFFFF), Offset(tl.x, tl.y + h * i / 3), Offset(br.x, tl.y + h * i / 3))
            }
            drawRect(Color.White, tl, Size(w, h), style = Stroke(2.dp.toPx()))
            for (c in listOf(tl, Offset(br.x, tl.y), Offset(tl.x, br.y), br)) {
                drawCircle(Color.White, 8.dp.toPx(), c)
            }
        }
        Text(
            "%d × %d px".format(rect.width.roundToInt(), rect.height.roundToInt()),
            color = Color(0xFFBDBDBD), modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onCancel) { Text("취소", color = Color.White) }
            OutlinedButton(onClick = { src = Raster.rotate90(src) }) { Text("90° 회전", color = Color.White) }
            OutlinedButton(onClick = { rect = Rect(0f, 0f, src.width.toFloat(), src.height.toFloat()) }) {
                Text("전체", color = Color.White)
            }
            Button(onClick = {
                val x = rect.left.roundToInt().coerceIn(0, src.width - 1)
                val y = rect.top.roundToInt().coerceIn(0, src.height - 1)
                val w = rect.width.roundToInt().coerceIn(1, src.width - x)
                val h = rect.height.roundToInt().coerceIn(1, src.height - y)
                onDone(if (x == 0 && y == 0 && w == src.width && h == src.height) src else Bitmap.createBitmap(src, x, y, w, h))
            }) { Text("완료") }
        }
    }
}
