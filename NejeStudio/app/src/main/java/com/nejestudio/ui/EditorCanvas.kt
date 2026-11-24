package com.nejestudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nejestudio.model.CanvasItem
import com.nejestudio.model.Geometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 작업 구역(px)을 화면에 맞추는 변환 */
private class ViewMap(val scale: Float, val ox: Float, val oy: Float) {
    fun toScreen(x: Float, y: Float) = Offset(ox + x * scale, oy + y * scale)
    fun toArea(p: Offset) = Offset((p.x - ox) / scale, (p.y - oy) / scale)
}

/** zoom = 1 이면 작업 구역 전체가 화면에 맞게 보인다. pan은 화면 px 이동량 */
private fun viewMap(g: Geometry, w: Float, h: Float, pad: Float, zoom: Float = 1f, pan: Offset = Offset.Zero): ViewMap {
    val s = min((w - 2 * pad) / g.widthPx, (h - 2 * pad) / g.heightPx) * zoom
    return ViewMap(s, (w - g.widthPx * s) / 2 + pan.x, (h - g.heightPx * s) / 2 + pan.y)
}

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 12f

@Composable
fun EditorCanvas(
    geometry: Geometry,
    items: List<CanvasItem>,
    previews: Map<Long, ImageBitmap>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    hitTest: (Float, Float) -> Long?,
    onTransform: (id: Long, pan: Offset, zoom: Float, centroid: Offset) -> Unit,
    onResize: (id: Long, w: Float, h: Float) -> Unit,
    modifier: Modifier = Modifier,
    /** 레이저 헤드 위치 (작업 구역 px). null이면 표시 안 함 */
    head: Offset? = null,
    /** 헤드 위치 안내 문구 (영점 기준 이동량) */
    headLabel: String? = null,
    /** '위치 확인' 중 헤드가 도는 테두리 (작업 구역 px, [x, y, w, h]) */
    frame: IntArray? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val geo by rememberUpdatedState(geometry)
    val itemsNow by rememberUpdatedState(items)
    val selNow by rememberUpdatedState(selectedId)

    // 화면 확대/이동 상태
    var viewZoom by remember { mutableFloatStateOf(1f) }
    var viewPan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val padPx = with(LocalDensity.current) { 16.dp.toPx() }
    // 작업 구역 크기가 바뀌면 전체 보기로
    LaunchedEffect(geometry.widthPx, geometry.heightPx) { viewZoom = 1f; viewPan = Offset.Zero }

    /** 화면 좌표 c를 고정점으로 확대/축소 */
    fun zoomAt(factor: Float, c: Offset) {
        val w = canvasSize.width.toFloat(); val h = canvasSize.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val before = viewMap(geo, w, h, padPx, viewZoom, viewPan)
        val nz = (viewZoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val a = before.toArea(c)
        val s1 = before.scale / viewZoom * nz
        viewPan = Offset(
            c.x - a.x * s1 - (w - geo.widthPx * s1) / 2,
            c.y - a.y * s1 - (h - geo.heightPx * s1) / 2,
        )
        viewZoom = nz
    }

    Box(modifier) {
    Canvas(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { canvasSize = it }.pointerInput(Unit) {
            val pad = padPx
            val handleR = 28.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val vm = viewMap(geo, size.width.toFloat(), size.height.toFloat(), pad, viewZoom, viewPan)
                val p = vm.toArea(down.position)

                // 선택된 개체의 크기 조절 핸들(우하단)을 잡았는지 확인
                val sel = itemsNow.find { it.id == selNow }
                val handleHit = sel != null &&
                    (down.position - vm.toScreen(sel.x + sel.w, sel.y + sel.h)).getDistance() < handleR
                val target: CanvasItem? = if (handleHit) sel else {
                    val id = hitTest(p.x, p.y)
                    onSelect(id)
                    itemsNow.find { it.id == id }
                }
                if (target == null) {
                    // 빈 곳: 한 손가락 = 화면 이동, 두 손가락 = 화면 확대/축소
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val c = event.calculateCentroid(useCurrent = false)
                        if (zoom != 1f && c != Offset.Unspecified) zoomAt(zoom, c)
                        if (pan != Offset.Zero) viewPan += pan
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    return@awaitEachGesture
                }
                val id = target.id
                val w0 = target.w; val h0 = target.h

                var pastSlop = false
                var accPan = Offset.Zero
                var accZoom = 1f
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.none { it.pressed }) break
                    if (handleHit) {
                        val ch = event.changes.first()
                        val cur = itemsNow.find { it.id == id } ?: break
                        val q = vm.toArea(ch.position)
                        val f = max((q.x - cur.x) / w0, (q.y - cur.y) / h0)
                        if (f * min(w0, h0) >= 4f) onResize(id, w0 * f, h0 * f)
                        ch.consume()
                        continue
                    }
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (!pastSlop) {
                        accPan += pan; accZoom *= zoom
                        if (accPan.getDistance() > viewConfiguration.touchSlop || abs(1f - accZoom) > 0.03f) pastSlop = true
                        else continue
                    }
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (zoom != 1f || pan != Offset.Zero) {
                        onTransform(id, pan / vm.scale, zoom, vm.toArea(centroid))
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        },
    ) {
        val vm = viewMap(geometry, size.width, size.height, padPx, viewZoom, viewPan)
        val areaTl = vm.toScreen(0f, 0f)
        val areaSize = Size(geometry.widthPx * vm.scale, geometry.heightPx * vm.scale)

        // 작업 구역 + 격자
        drawRect(Color.White, areaTl, areaSize)
        drawGrid(geometry, vm, areaTl, areaSize)
        drawRect(Color(0xFF616161), areaTl, areaSize, style = Stroke(1.5.dp.toPx()))

        // 개체
        for (item in items) {
            val tl = vm.toScreen(item.x, item.y)
            val sz = Size(item.w * vm.scale, item.h * vm.scale)
            val img = previews[item.id]
            if (img != null) {
                drawImage(
                    img,
                    srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
                    dstSize = IntSize(max(1, sz.width.roundToInt()), max(1, sz.height.roundToInt())),
                )
            } else {
                drawRect(Color(0x22000000), tl, sz)
            }
            if (!geometry.isInside(item)) {
                drawRect(Color(0xFFD32F2F), tl, sz, style = Stroke(1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
            }
        }

        // 선택 표시
        items.find { it.id == selectedId }?.let { item ->
            val tl = vm.toScreen(item.x, item.y)
            val sz = Size(item.w * vm.scale, item.h * vm.scale)
            val accent = Color(0xFF1E88E5)
            drawRect(accent, tl, sz, style = Stroke(2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
            val hc = Offset(tl.x + sz.width, tl.y + sz.height)
            drawCircle(Color.White, 9.dp.toPx(), hc)
            drawCircle(accent, 9.dp.toPx(), hc, style = Stroke(2.5.dp.toPx()))
            // 크기 라벨
            val label = "%.1f × %.1f mm".format(geometry.toMm(item.w), geometry.toMm(item.h))
            val layout = textMeasurer.measure(label, TextStyle(fontSize = 11.sp, color = Color.White))
            val lx = tl.x.coerceIn(0f, max(0f, size.width - layout.size.width - 8f))
            val ly = (tl.y - layout.size.height - 6.dp.toPx()).coerceAtLeast(0f)
            drawRect(accent, Offset(lx, ly), Size(layout.size.width + 8f, layout.size.height.toFloat()))
            drawText(layout, topLeft = Offset(lx + 4f, ly))
        }

        // 위치 확인 테두리 (헤드가 이 선을 따라 돈다)
        if (frame != null) {
            val ftl = vm.toScreen(frame[0].toFloat(), frame[1].toFloat())
            val fsz = Size(frame[2] * vm.scale, frame[3] * vm.scale)
            val c = Color(0xFFFF6D00)
            drawRect(c.copy(alpha = 0.08f), ftl, fsz)
            drawRect(c, ftl, fsz, style = Stroke(2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
            val corners = listOf(ftl, Offset(ftl.x + fsz.width, ftl.y),
                Offset(ftl.x + fsz.width, ftl.y + fsz.height), Offset(ftl.x, ftl.y + fsz.height))
            corners.forEach { drawCircle(c, 3.5.dp.toPx(), it) }
        }

        // 레이저 헤드 위치 (십자선)
        if (head != null) {
            val hp = vm.toScreen(head.x, head.y)
            val headColor = Color(0xFFFF6D00)
            val r = 10.dp.toPx()
            val sw = 2.dp.toPx()
            drawLine(headColor, Offset(hp.x - r * 1.6f, hp.y), Offset(hp.x + r * 1.6f, hp.y), sw)
            drawLine(headColor, Offset(hp.x, hp.y - r * 1.6f), Offset(hp.x, hp.y + r * 1.6f), sw)
            drawCircle(headColor, r, hp, style = Stroke(sw))
            drawCircle(headColor, 2.5.dp.toPx(), hp)
        }

        // 영점 표시
        val o = geometry.area.origin
        val corner = Offset(
            if (o.isLeft) areaTl.x else areaTl.x + areaSize.width,
            if (o.isTop) areaTl.y else areaTl.y + areaSize.height,
        )
        drawCircle(Color(0xFFE53935), 6.dp.toPx(), corner)
        val zl = textMeasurer.measure("0", TextStyle(fontSize = 11.sp, color = Color(0xFFE53935)))
        val zx = if (o.isLeft) corner.x - zl.size.width - 8.dp.toPx() else corner.x + 8.dp.toPx()
        val zy = if (o.isTop) corner.y - zl.size.height else corner.y
        drawText(zl, topLeft = Offset(zx.coerceIn(0f, size.width - zl.size.width), zy.coerceIn(0f, size.height - zl.size.height)))
    }

    // 헤드 위치 안내
    if (headLabel != null) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = Color(0xE6263238),
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        ) {
            Text(
                headLabel,
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFFFB74D),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }

    // 확대/축소 버튼
    Column(
        Modifier.align(Alignment.TopEnd).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
        ZoomButton("+") { zoomAt(1.5f, center) }
        ZoomButton("−") { zoomAt(1 / 1.5f, center) }
        ZoomButton("맞춤") { viewZoom = 1f; viewPan = Offset.Zero }
        Text(
            "${(viewZoom * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF37474F),
        )
    }
    }
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.9f),
        shadowElevation = 2.dp,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = if (label.length > 1) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium,
                color = Color(0xFF263238))
        }
    }
}

private fun DrawScope.drawGrid(g: Geometry, vm: ViewMap, tl: Offset, sz: Size) {
    val pxPerMm = vm.scale / g.mmPerPx
    val step = when {
        pxPerMm * 1 >= 8 -> 1f
        pxPerMm * 5 >= 8 -> 5f
        else -> 10f
    }
    val minor = Color(0xFFEEEEEE); val major = Color(0xFFCFD8DC)
    var mm = step
    while (mm < g.area.widthMm) {
        val x = if (g.area.origin.isLeft) tl.x + mm * pxPerMm else tl.x + sz.width - mm * pxPerMm
        drawLine(if (mm % 10f == 0f) major else minor, Offset(x, tl.y), Offset(x, tl.y + sz.height))
        mm += step
    }
    mm = step
    while (mm < g.area.heightMm) {
        val y = if (g.area.origin.isTop) tl.y + mm * pxPerMm else tl.y + sz.height - mm * pxPerMm
        drawLine(if (mm % 10f == 0f) major else minor, Offset(tl.x, y), Offset(tl.x + sz.width, y))
        mm += step
    }
}
