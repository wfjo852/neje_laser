package com.nejestudio.model

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** 영점(원점) 위치. 작업 구역은 이 모서리를 기준으로 기기 작업 범위 안에 배치된다. */
enum class Origin(val label: String, val isLeft: Boolean, val isTop: Boolean) {
    TOP_LEFT("좌상단", true, true),
    TOP_RIGHT("우상단", false, true),
    BOTTOM_LEFT("좌하단", true, false),
    BOTTOM_RIGHT("우하단", false, false),
}

data class WorkArea(val widthMm: Float = 40f, val heightMm: Float = 40f, val origin: Origin = Origin.TOP_LEFT)

/** 기기 사양. 연결되면 기기 값으로 갱신되고 저장된다. */
data class MachineProfile(val maxWidth: Int = 550, val maxHeight: Int = 550, val mmPerPixel: Float = 0.075f)

/**
 * 모터 방향 반전. 기기에 직접 명령이 없으므로 앱에서 좌표와 이미지를 뒤집어 보정한다.
 * 논리 좌표(좌상단 = 0,0)를 실제로 기기에 보낼 좌표로 바꾼다.
 */
data class AxisConfig(val invertX: Boolean = false, val invertY: Boolean = false) {
    fun mapX(x: Int, w: Int, maxW: Int) = if (invertX) maxW - x - w else x
    fun mapY(y: Int, h: Int, maxH: Int) = if (invertY) maxH - y - h else y
}

data class Preset(val id: Long, val name: String, val carvingTime: Int, val power: Int)

enum class ItemKind { IMAGE, TEXT }

enum class RenderMode(val label: String) {
    THRESHOLD("흑백"),
    DITHER("디더링"),
    OUTLINE("테두리"),
}

enum class FontKind(val label: String) { SANS("고딕"), SERIF("명조"), MONO("고정폭") }

data class TextSpec(
    val text: String,
    val sizeMm: Float = 5f,
    val bold: Boolean = false,
    val font: FontKind = FontKind.SANS,
)

/**
 * 캔버스 위의 개체. 좌표/크기 단위는 작업 구역 기준 기기 픽셀 (좌상단 = 0,0, y는 아래로 증가).
 */
data class CanvasItem(
    val id: Long,
    val kind: ItemKind,
    val source: Bitmap,
    val text: TextSpec? = null,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val mode: RenderMode = RenderMode.THRESHOLD,
    val threshold: Int = 128,
    val invert: Boolean = false,
    val thickness: Int = 2,
    val version: Int = 0,
) {
    val renderKey: String
        get() = "${w.roundToInt()}x${h.roundToInt()}/$mode/$threshold/$invert/$thickness/$version"

    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
}

/** 작업 구역과 기기 좌표 사이의 변환 */
data class Geometry(val area: WorkArea, val machine: MachineProfile) {
    val mmPerPx: Float get() = machine.mmPerPixel
    val widthPx: Int = (area.widthMm / machine.mmPerPixel).roundToInt().coerceIn(1, machine.maxWidth)
    val heightPx: Int = (area.heightMm / machine.mmPerPixel).roundToInt().coerceIn(1, machine.maxHeight)

    /** 작업 구역 좌상단의 기기 좌표 */
    val deviceLeft: Int = if (area.origin.isLeft) 0 else machine.maxWidth - widthPx
    val deviceTop: Int = if (area.origin.isTop) 0 else machine.maxHeight - heightPx

    val maxWidthMm: Float get() = machine.maxWidth * mmPerPx
    val maxHeightMm: Float get() = machine.maxHeight * mmPerPx

    fun toMm(px: Float) = px * mmPerPx
    fun toPx(mm: Float) = mm / mmPerPx

    /** 영점에서 개체의 가장 가까운 모서리까지의 거리 (mm) */
    fun userX(it: CanvasItem) = toMm(if (area.origin.isLeft) it.x else widthPx - it.x - it.w)
    fun userY(it: CanvasItem) = toMm(if (area.origin.isTop) it.y else heightPx - it.y - it.h)

    fun xFromUser(mm: Float, w: Float) = if (area.origin.isLeft) toPx(mm) else widthPx - toPx(mm) - w
    fun yFromUser(mm: Float, h: Float) = if (area.origin.isTop) toPx(mm) else heightPx - toPx(mm) - h

    fun isInside(it: CanvasItem) =
        it.x >= -0.5f && it.y >= -0.5f && it.x + it.w <= widthPx + 0.5f && it.y + it.h <= heightPx + 0.5f
}
