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
import com.nejestudio.model.AxisConfig
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

    val axis = MutableStateFlow(store.loadAxis())

    /** 레이저 헤드의 현재 위치 (논리 기기 좌표, px). 알 수 없으면 null */
    val headPos = MutableStateFlow<IntArray?>(null)
    val jogStepMm = MutableStateFlow(5f)
    val jogging = MutableStateFlow(false)

    val presets = MutableStateFlow(store.loadPresets())
    val items = MutableStateFlow<List<CanvasItem>>(emptyList())
    val selectedId = MutableStateFlow<Long?>(null)
    val previews = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())

    val carvingTime = MutableStateFlow(store.lastCarvingTime)
    val power = MutableStateFlow(store.lastPower)
    val activePresetId = MutableStateFlow<Long?>(null)

    val framing = MutableStateFlow(false)
    /** '위치 확인' 중 헤드가 도는 테두리 (작업 구역 px, [x, y, w, h]) */
    val frameArea = MutableStateFlow<IntArray?>(null)
    val busy = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    private var nextId = 1L
    private var lastUserSettingChange = 0L
    private var settingJob: Job? = null
    private var framingJob: Job? = null
    private var autoStopJob: Job? = null
    private var lastFrame: IntArray? = null

    // 주의: 아래 init 블록은 생성 중에 바로 실행되므로, init에서 쓰는 속성은 반드시 이 위에 선언할 것
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
            ble.connection.collect {
                if (it != NejeBle.Conn.CONNECTED) {
                    framing.value = false
                    frameArea.value = null
                    jogging.value = false
                    headPos.value = null
                }
            }
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

    /** x, y = 기기로 보낼 좌표. area = 작업 구역 기준 잉크 영역 [x, y, w, h] (화면 표시용) */
    private class Job2(val image: EncodedImage, val x: Int, val y: Int, val area: IntArray)

    /** 위치 확인 테두리를 영점 기준 mm 문구로 */
    fun frameLabel(a: IntArray): String {
        val g = geometry.value
        val ux = if (g.area.origin.isLeft) a[0] else g.widthPx - a[0] - a[2]
        val uy = if (g.area.origin.isTop) a[1] else g.heightPx - a[1] - a[3]
        return "위치 확인 중 · X %.1f mm · Y %.1f mm · %.1f × %.1f mm".format(
            g.toMm(ux.toFloat()), g.toMm(uy.toFloat()), g.toMm(a[2].toFloat()), g.toMm(a[3].toFloat()),
        )
    }

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
        val ax = axis.value
        // 모터 방향 반전 보정: 이미지를 뒤집고 좌표를 반대편 기준으로 바꾼다
        val sub = BooleanArray(bw * bh) { i ->
            val sx = if (ax.invertX) bw - 1 - i % bw else i % bw
            val sy = if (ax.invertY) bh - 1 - i / bw else i / bw
            mask[(minY + sy) * W + minX + sx]
        }
        Job2(
            Protocol.encodeImage(sub, bw, bh),
            ax.mapX(g.deviceLeft + minX, bw, g.machine.maxWidth),
            ax.mapY(g.deviceTop + minY, bh, g.machine.maxHeight),
            intArrayOf(minX, minY, bw, bh),
        )
    }

    fun setAxis(a: AxisConfig) {
        axis.value = a
        store.saveAxis(a)
        headPos.value = null
    }

    // ------------------------------------------------------------ 헤드 이동 (테스트용)
    // 기기에 이동 명령이 없으므로 '위치 확인' 명령으로 아주 작은 사각형을 그리게 해서 헤드를 이동시킨다.

    private fun canMoveHead(): Boolean {
        if (ble.connection.value != NejeBle.Conn.CONNECTED) { messages.tryEmit("먼저 각인기를 연결하세요"); return false }
        val st = ble.status.value.state
        if (st == MachineState.CARVING || st == MachineState.PAUSED) { messages.tryEmit("각인 중에는 헤드를 움직일 수 없습니다"); return false }
        if (busy.value) return false
        return true
    }

    /** 영점 모서리의 논리 좌표 */
    private fun originPos(): IntArray {
        val g = geometry.value; val m = g.machine
        return intArrayOf(
            if (g.area.origin.isLeft) 0 else m.maxWidth - JOG_DOT,
            if (g.area.origin.isTop) 0 else m.maxHeight - JOG_DOT,
        )
    }

    private suspend fun moveHeadTo(x: Int, y: Int) {
        val g = geometry.value; val m = g.machine
        val nx = x.coerceIn(0, m.maxWidth - JOG_DOT)
        val ny = y.coerceIn(0, m.maxHeight - JOG_DOT)
        if (framing.value) { framingJob?.cancel(); framing.value = false; frameArea.value = null }
        autoStopJob?.cancel()
        val prev = headPos.value
        val ax = axis.value
        headPos.value = intArrayOf(nx, ny)
        jogging.value = true
        val dx = ax.mapX(nx, JOG_DOT, m.maxWidth); val dy = ax.mapY(ny, JOG_DOT, m.maxHeight)
        ble.send(Protocol.frame(Protocol.FRAME_MODE_PREVIEW, dx, dy, JOG_DOT, JOG_DOT))

        // 작은 사각형을 계속 그리면 모터가 떨리므로, 헤드가 도착할 시간이 지나면 표시를 자동으로 끈다.
        // 이전 위치를 모르면 기기 전체 대각선 거리만큼 이동한다고 가정한다.
        val distMm = if (prev != null) {
            g.toMm(kotlin.math.hypot((nx - prev[0]).toFloat(), (ny - prev[1]).toFloat()))
        } else g.toMm(kotlin.math.hypot(m.maxWidth.toFloat(), m.maxHeight.toFloat()))
        val waitMs = (AUTO_STOP_BASE_MS + distMm * AUTO_STOP_MS_PER_MM).toLong().coerceAtMost(AUTO_STOP_MAX_MS)
        autoStopJob = viewModelScope.launch {
            delay(waitMs)
            if (!jogging.value) return@launch
            jogging.value = false
            ble.send(Protocol.frame(Protocol.FRAME_MODE_STOP, dx, dy, JOG_DOT, JOG_DOT))
        }
    }

    /** dx, dy: 화면 방향 기준 -1/0/1 (오른쪽 = +X, 아래 = +Y) */
    fun jog(dx: Int, dy: Int) {
        if (!canMoveHead()) return
        val g = geometry.value
        val step = g.toPx(jogStepMm.value).roundToInt().coerceAtLeast(1)
        val cur = headPos.value ?: originPos()
        viewModelScope.launch { moveHeadTo(cur[0] + dx * step, cur[1] + dy * step) }
    }

    /** 레이저 헤드를 영점으로 */
    fun goHome() {
        if (!canMoveHead()) return
        val o = originPos()
        viewModelScope.launch { moveHeadTo(o[0], o[1]) }
    }

    /** 이동 표시(약광) 끄기 */
    fun stopJog() {
        if (ble.connection.value != NejeBle.Conn.CONNECTED) return
        val p = headPos.value ?: return
        val m = geometry.value.machine; val ax = axis.value
        autoStopJob?.cancel()
        jogging.value = false
        viewModelScope.launch {
            ble.send(Protocol.frame(Protocol.FRAME_MODE_STOP,
                ax.mapX(p[0], JOG_DOT, m.maxWidth), ax.mapY(p[1], JOG_DOT, m.maxHeight), JOG_DOT, JOG_DOT))
        }
    }

    /** 헤드 위치를 영점 기준 mm로 */
    fun headUserMm(p: IntArray): Pair<Float, Float> {
        val g = geometry.value; val m = g.machine
        val ux = if (g.area.origin.isLeft) p[0] else m.maxWidth - JOG_DOT - p[0]
        val uy = if (g.area.origin.isTop) p[1] else m.maxHeight - JOG_DOT - p[1]
        return g.toMm(ux.toFloat()) to g.toMm(uy.toFloat())
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
                jogging.value = false
                headPos.value = null
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
        jogging.value = false
        headPos.value = null
        framingJob = viewModelScope.launch {
            combine(items, geometry, axis) { a, b, c -> Triple(a, b, c) }.collectLatest {
                delay(250)
                val job = composeJob()
                if (job == null) { frameArea.value = null; messages.emit("표시할 내용이 없습니다"); return@collectLatest }
                frameArea.value = job.area
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
        frameArea.value = null
        val f = lastFrame ?: intArrayOf(0, 0, 0, 0)
        lastFrame = null
        ble.send(Protocol.frame(Protocol.FRAME_MODE_STOP, f[0], f[1], f[2], f[3]))
    }

    companion object {
        /** 헤드 이동 시 그리는 사각형 크기 (px) */
        const val JOG_DOT = 2

        /**
         * 헤드 이동 후 레이저 표시를 자동으로 끄기까지의 대기 시간 = 기본 + 거리(mm) × 값.
         * 0이면 이동 명령 직후 바로 끈다. 헤드가 목표까지 못 가고 멈추면 값을 늘린다.
         */
        const val AUTO_STOP_BASE_MS = 0f
        const val AUTO_STOP_MS_PER_MM = 0f
        const val AUTO_STOP_MAX_MS = 6000L
    }

    /** 헤드 위치를 작업 구역 좌표(px)로. 캔버스 표시용 */
    fun headInArea(p: IntArray): Offset {
        val g = geometry.value
        return Offset(p[0] - g.deviceLeft + JOG_DOT / 2f, p[1] - g.deviceTop + JOG_DOT / 2f)
    }

    override fun onCleared() {
        ble.disconnect()
    }
}
