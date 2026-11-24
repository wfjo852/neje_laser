package com.nejestudio

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nejestudio.ble.DeviceSetting
import com.nejestudio.ble.EncodedImage
import com.nejestudio.ble.MachineState
import com.nejestudio.ble.NejeBle
import com.nejestudio.ble.Protocol
import com.nejestudio.data.Store
import com.nejestudio.imaging.Raster
import com.nejestudio.imaging.TextRenderer
import com.nejestudio.model.CanvasItem
import com.nejestudio.model.Geometry
import com.nejestudio.model.ItemKind
import com.nejestudio.model.MachineProfile
import com.nejestudio.model.Preset
import com.nejestudio.model.RenderMode
import com.nejestudio.model.TextSpec
import com.nejestudio.model.WorkArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val ble = NejeBle(app, viewModelScope)
    private val store = Store(app)

    val workArea = MutableStateFlow(store.loadWorkArea())
    val machine = MutableStateFlow(store.loadMachine())
    val geometry: StateFlow<Geometry> = combine(workArea, machine) { wa, m -> Geometry(wa, m) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Geometry(workArea.value, machine.value))

    val presets = MutableStateFlow(store.loadPresets())
    val items = MutableStateFlow<List<CanvasItem>>(emptyList())
    val selectedId = MutableStateFlow<Long?>(null)
    val previews = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())

    val carvingTime = MutableStateFlow(store.lastCarvingTime)
    val power = MutableStateFlow(store.lastPower)
    val activePresetId = MutableStateFlow<Long?>(null)

    val framing = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    private var nextId = 1L
    private var lastUserSettingChange = 0L
    private var settingJob: Job? = null
    private var framingJob: Job? = null
    private var lastFrame: IntArray? = null
    private val previewCache = HashMap<Long, Pair<String, ImageBitmap>>()

    init {
        viewModelScope.launch { ble.messages.collect { messages.emit(it) } }
        viewModelScope.launch {
            ble.info.filterNotNull().collect { i ->
                val m = MachineProfile(i.maxWidth, i.maxHeight, i.mmPerPixel)
                if (m.maxWidth > 0 && m.maxHeight > 0 && m.mmPerPixel > 0f) {
                    machine.value = m
                    store.saveMachine(m)
                }
                ble.connectedAddress.value?.let { store.lastAddress = it }
            }
        }
        viewModelScope.launch {
            ble.setting.filterNotNull().collect { s ->
                // 사용자가 방금 조절한 값은 덮어쓰지 않는다
                if (SystemClock.elapsedRealtime() - lastUserSettingChange > 1500) {
                    carvingTime.value = s.carvingTime.coerceIn(0, 100)
                    power.value = s.power.coerceIn(1, 100)
                }
            }
        }
        viewModelScope.launch {
            ble.connection.collect { if (it != NejeBle.Conn.CONNECTED) framing.value = false }
        }
        viewModelScope.launch { items.collectLatest { delay(80); updatePreviews(it) } }
    }

    val lastAddress: String? get() = store.lastAddress

    // ------------------------------------------------------------ 미리보기

    private suspend fun updatePreviews(list: List<CanvasItem>) {
        val result = HashMap<Long, ImageBitmap>()
        for (item in list) {
            val cached = previewCache[item.id]
            if (cached != null && cached.first == item.renderKey) {
                result[item.id] = cached.second
                continue
            }
            val img = withContext(Dispatchers.Default) {
                val w = max(1, item.w.roundToInt()); val h = max(1, item.h.roundToInt())
                // 화면 미리보기는 최대 1200px로 제한
                val s = min(1f, 1200f / max(w, h))
                val pw = max(1, (w * s).roundToInt()); val ph = max(1, (h * s).roundToInt())
                val mask = Raster.render(item, pw, ph, s)
                ensureActive()
                Raster.maskToBitmap(mask, pw, ph).asImageBitmap()
            }
            previewCache[item.id] = item.renderKey to img
            result[item.id] = img
            previews.value = previews.value + (item.id to img)
        }
        previewCache.keys.retainAll(list.map { it.id }.toSet())
        previews.value = result
    }

    // ------------------------------------------------------------ 개체 추가/편집

    private fun fitSize(srcW: Float, srcH: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
        val s = min(maxW / srcW, maxH / srcH)
        return srcW * s to srcH * s
    }

    fun addImage(bmp: Bitmap) {
        val g = geometry.value
        val (w, h) = fitSize(bmp.width.toFloat(), bmp.height.toFloat(), g.widthPx * 0.8f, g.heightPx * 0.8f)
        val item = CanvasItem(
            id = nextId++, kind = ItemKind.IMAGE, source = bmp,
            x = (g.widthPx - w) / 2, y = (g.heightPx - h) / 2, w = w, h = h,
            mode = RenderMode.THRESHOLD,
        )
        items.update { it + item }
        selectedId.value = item.id
    }

    private fun textSize(spec: TextSpec, bmp: Bitmap, g: Geometry): Pair<Float, Float> {
        val k = g.toPx(spec.sizeMm) / TextRenderer.BASE_SIZE
        var w = bmp.width * k; var h = bmp.height * k
        if (w > g.widthPx || h > g.heightPx) {
            val f = fitSize(w, h, g.widthPx.toFloat(), g.heightPx.toFloat()); w = f.first; h = f.second
        }
        return w to h
    }

    fun addText(spec: TextSpec) {
        val g = geometry.value
        val bmp = TextRenderer.render(spec)
        val (w, h) = textSize(spec, bmp, g)
        val item = CanvasItem(
            id = nextId++, kind = ItemKind.TEXT, source = bmp, text = spec,
            x = (g.widthPx - w) / 2, y = (g.heightPx - h) / 2, w = w, h = h,
        )
        items.update { it + item }
        selectedId.value = item.id
    }

    fun updateText(id: Long, spec: TextSpec) {
        val g = geometry.value
        val bmp = TextRenderer.render(spec)
        val (w, h) = textSize(spec, bmp, g)
        update(id) { it.copy(source = bmp, text = spec, w = w, h = h, version = it.version + 1) }
    }

    /** 잘라내기 결과로 원본 교체. 원본 1px 당 크기는 유지 */
    fun replaceSource(id: Long, bmp: Bitmap) = update(id) {
        val k = it.w / it.source.width
        it.copy(source = bmp, w = bmp.width * k, h = bmp.height * k, version = it.version + 1)
    }

    fun rotate(id: Long) = update(id) {
        val cx = it.x + it.w / 2; val cy = it.y + it.h / 2
        it.copy(
            source = Raster.rotate90(it.source), w = it.h, h = it.w,
            x = cx - it.h / 2, y = cy - it.w / 2, version = it.version + 1,
        )
    }

    fun update(id: Long, f: (CanvasItem) -> CanvasItem) {
        items.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    fun select(id: Long?) { selectedId.value = id }

    fun delete(id: Long) {
        items.update { list -> list.filterNot { it.id == id } }
        if (selectedId.value == id) selectedId.value = null
    }

    fun bringToFront(id: Long) = items.update { list ->
        val it = list.find { i -> i.id == id } ?: return@update list
        list.filterNot { i -> i.id == id } + it
    }

    fun center(id: Long) {
        val g = geometry.value
        update(id) { it.copy(x = (g.widthPx - it.w) / 2, y = (g.heightPx - it.h) / 2) }
    }

    /** 선택한 개체를 영점 모서리에 붙인다 (X=0, Y=0) */
    fun moveToOrigin(id: Long) = setUserRect(id, 0f, 0f, null, null, true)

    /** 모든 개체를 하나의 묶음으로 보고, 묶음의 모서리를 영점에 맞춘다 (배치 간격은 유지) */
    fun alignAllToOrigin() {
        val list = items.value
        if (list.isEmpty()) return
        val g = geometry.value
        val minX = list.minOf { it.x }; val maxX = list.maxOf { it.x + it.w }
        val minY = list.minOf { it.y }; val maxY = list.maxOf { it.y + it.h }
        val dx = if (g.area.origin.isLeft) -minX else g.widthPx - maxX
        val dy = if (g.area.origin.isTop) -minY else g.heightPx - maxY
        items.value = list.map { it.copy(x = it.x + dx, y = it.y + dy) }
    }

    fun hitTest(px: Float, py: Float): Long? = items.value.lastOrNull { it.contains(px, py) }?.id

    /** 손가락 이동/핀치. pan은 작업구역 px, centroid는 작업구역 좌표 */
    fun transform(id: Long, pan: Offset, zoom: Float, centroid: Offset) = update(id) {
        val minSide = min(it.w, it.h)
        val z = if (minSide * zoom < 4f) 1f else zoom
        it.copy(
            x = centroid.x - (centroid.x - it.x) * z + pan.x,
            y = centroid.y - (centroid.y - it.y) * z + pan.y,
            w = it.w * z, h = it.h * z,
        )
    }

    fun setSize(id: Long, w: Float, h: Float) = update(id) {
        it.copy(w = max(2f, w), h = max(2f, h))
    }

    /** 사용자가 입력한 mm 값으로 위치/크기 설정 (영점 기준) */
    fun setUserRect(id: Long, xMm: Float?, yMm: Float?, wMm: Float?, hMm: Float?, lockAspect: Boolean) {
        val g = geometry.value
        update(id) { it0 ->
            var w = it0.w; var h = it0.h
            if (wMm != null && wMm > 0) {
                w = g.toPx(wMm); if (lockAspect) h = it0.h * w / it0.w
            } else if (hMm != null && hMm > 0) {
                h = g.toPx(hMm); if (lockAspect) w = it0.w * h / it0.h
            }
            // 크기를 바꿀 때 영점 쪽 모서리를 고정
            val curX = g.userX(it0); val curY = g.userY(it0)
            val nx = g.xFromUser(xMm ?: curX, w)
            val ny = g.yFromUser(yMm ?: curY, h)
            it0.copy(x = nx, y = ny, w = w, h = h)
        }
    }

    // ------------------------------------------------------------ 작업 구역

    fun setWorkArea(wa: WorkArea) {
        workArea.value = wa
        store.saveWorkArea(wa)
    }

    // ------------------------------------------------------------ 레이저 설정 / 프리셋

    fun setCarvingTime(v: Int) { carvingTime.value = v; activePresetId.value = null; scheduleSettingPush() }
    fun setPower(v: Int) { power.value = v.coerceAtLeast(1); activePresetId.value = null; scheduleSettingPush() }

    fun applyPreset(p: Preset) {
        carvingTime.value = p.carvingTime
        power.value = p.power
        activePresetId.value = p.id
        scheduleSettingPush()
    }

    fun savePreset(name: String) {
        val p = Preset(System.currentTimeMillis(), name.ifBlank { "프리셋 ${presets.value.size + 1}" }, carvingTime.value, power.value)
        presets.update { it + p }
        store.savePresets(presets.value)
        activePresetId.value = p.id
    }

    fun deletePreset(id: Long) {
        presets.update { list -> list.filterNot { it.id == id } }
        store.savePresets(presets.value)
        if (activePresetId.value == id) activePresetId.value = null
    }

    private fun scheduleSettingPush() {
        lastUserSettingChange = SystemClock.elapsedRealtime()
        store.lastCarvingTime = carvingTime.value
        store.lastPower = power.value
        settingJob?.cancel()
        settingJob = viewModelScope.launch {
            delay(200)
            pushSettings()
        }
    }

    private suspend fun pushSettings() {
        if (ble.connection.value != NejeBle.Conn.CONNECTED) return
        val s = (ble.setting.value ?: DeviceSetting()).copy(carvingTime = carvingTime.value, power = power.value)
        ble.setting.value = s
        ble.send(Protocol.setting(s))
    }

    /** 설정 화면의 기기 고급 설정 (약광, 기울기 감도, 빠른 모드) */
    fun updateDeviceSetting(f: (DeviceSetting) -> DeviceSetting) {
        val base = (ble.setting.value ?: DeviceSetting()).copy(carvingTime = carvingTime.value, power = power.value)
        lastUserSettingChange = SystemClock.elapsedRealtime()
        ble.setting.value = f(base)
        settingJob?.cancel()
        settingJob = viewModelScope.launch {
            delay(200)
            ble.setting.value?.let { ble.send(Protocol.setting(it)) }
        }
    }

    // ------------------------------------------------------------ 각인

    private class Job2(val image: EncodedImage, val x: Int, val y: Int)

    /** 캔버스 전체를 1비트 비트맵 하나로 합성하고 잉크 영역만 잘라낸다 */
    private suspend fun composeJob(): Job2? = withContext(Dispatchers.Default) {
        val g = geometry.value
        val W = g.widthPx; val H = g.heightPx
        val mask = BooleanArray(W * H)
        for (item in items.value) {
            val iw = item.w.roundToInt(); val ih = item.h.roundToInt()
            if (iw < 1 || ih < 1) continue
            val m = Raster.render(item, iw, ih)
            val ox = item.x.roundToInt(); val oy = item.y.roundToInt()
            for (y in 0 until ih) {
                val ty = oy + y
                if (ty < 0 || ty >= H) continue
                for (x in 0 until iw) {
                    val tx = ox + x
                    if (tx < 0 || tx >= W) continue
                    if (m[y * iw + x]) mask[ty * W + tx] = true
                }
            }
        }
        var minX = W; var minY = H; var maxX = -1; var maxY = -1
        for (y in 0 until H) for (x in 0 until W) if (mask[y * W + x]) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < 0) return@withContext null
        val bw = maxX - minX + 1; val bh = maxY - minY + 1
        val sub = BooleanArray(bw * bh) { i -> mask[(minY + i / bw) * W + minX + i % bw] }
        Job2(Protocol.encodeImage(sub, bw, bh), g.deviceLeft + minX, g.deviceTop + minY)
    }

    fun startOrPause() {
        if (ble.connection.value != NejeBle.Conn.CONNECTED) { messages.tryEmit("먼저 각인기를 연결하세요"); return }
        when (ble.status.value.state) {
            MachineState.CARVING -> ble.sendAsync(Protocol.PAUSE)
            MachineState.PAUSED -> ble.sendAsync(Protocol.RESUME)
            else -> engrave()
        }
    }

    private fun engrave() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                if (framing.value) stopFraming()
                val job = composeJob()
                if (job == null) { messages.emit("작업 구역 안에 각인할 내용이 없습니다"); return@launch }
                pushSettings()
                ble.uploadImage(job.image, job.x, job.y)
            } finally {
                busy.value = false
            }
        }
    }

    fun stop() {
        if (ble.connection.value != NejeBle.Conn.CONNECTED) return
        ble.sendAsync(Protocol.STOP)
    }

    fun resetTilt() {
        viewModelScope.launch { ble.send(Protocol.RESET_TILT) }
    }

    /** 위치 확인: 약광으로 각인될 영역의 테두리를 표시하고 개체가 움직이면 따라간다 */
    fun toggleFraming() {
        if (framing.value) { viewModelScope.launch { stopFraming() }; return }
        if (ble.connection.value != NejeBle.Conn.CONNECTED) { messages.tryEmit("먼저 각인기를 연결하세요"); return }
        if (ble.status.value.state == MachineState.CARVING) { messages.tryEmit("각인 중에는 위치 확인을 할 수 없습니다"); return }
        framing.value = true
        framingJob = viewModelScope.launch {
            combine(items, geometry) { a, b -> a to b }.collectLatest {
                delay(250)
                val job = composeJob()
                if (job == null) { messages.emit("표시할 내용이 없습니다"); return@collectLatest }
                val f = intArrayOf(job.x, job.y, job.image.width, job.image.height)
                if (!f.contentEquals(lastFrame)) {
                    lastFrame = f
                    ble.send(Protocol.frame(Protocol.FRAME_MODE_PREVIEW, f[0], f[1], f[2], f[3]))
                }
            }
        }
    }

    private suspend fun stopFraming() {
        framingJob?.cancel()
        framing.value = false
        val f = lastFrame ?: intArrayOf(0, 0, 0, 0)
        lastFrame = null
        ble.send(Protocol.frame(Protocol.FRAME_MODE_STOP, f[0], f[1], f[2], f[3]))
    }

    override fun onCleared() {
        ble.disconnect()
    }
}
