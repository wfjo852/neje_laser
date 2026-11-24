package com.nejestudio.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * NEJE 각인기와의 BLE 통신을 담당한다.
 * 모든 GATT 쓰기는 [gattLock]으로 직렬화되며, 각 쓰기는 onCharacteristicWrite 콜백을 기다린다.
 */
@SuppressLint("MissingPermission")
class NejeBle(context: Context, private val scope: CoroutineScope) {

    data class Found(val name: String, val address: String, val rssi: Int)

    enum class Conn { DISCONNECTED, CONNECTING, CONNECTED }

    sealed interface Upload {
        data object Idle : Upload
        data class Sending(val fraction: Float) : Upload
        data object Waiting : Upload
    }

    data class Status(
        val state: MachineState = MachineState.UNKNOWN,
        val battery: Int? = null,
        val laserTemp: Int? = null,
        val batteryTemp: Int? = null,
        val progress: Int = 0,
        val elapsedSec: Int = 0,
        val tilt: Boolean = false,
        val homing: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val connection = MutableStateFlow(Conn.DISCONNECTED)
    val connectedAddress = MutableStateFlow<String?>(null)
    val scanning = MutableStateFlow(false)
    val found = MutableStateFlow<List<Found>>(emptyList())
    val info = MutableStateFlow<DeviceInfo?>(null)
    val status = MutableStateFlow(Status())
    val setting = MutableStateFlow<DeviceSetting?>(null)
    val upload = MutableStateFlow<Upload>(Upload.Idle)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val isAdapterEnabled: Boolean get() = adapter?.isEnabled == true

    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    private val parser = FrameParser()
    private val gattLock = Mutex()

    @Volatile private var writeAck: CompletableDeferred<Boolean>? = null
    @Volatile private var descAck: CompletableDeferred<Boolean>? = null
    @Volatile private var mtuAck: CompletableDeferred<Int>? = null
    @Volatile private var infoAck: CompletableDeferred<DeviceInfo?>? = null
    @Volatile private var readyAck: CompletableDeferred<Boolean>? = null
    @Volatile private var doneAck: CompletableDeferred<Boolean>? = null

    private var timeoutJob: Job? = null
    private var setupJob: Job? = null
    private var pollJob: Job? = null
    private var scanStopJob: Job? = null

    // ---------------------------------------------------------------- 검색

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val f = Found(name, result.device.address, result.rssi)
            found.update { list ->
                if (list.any { it.address == f.address }) list.map { if (it.address == f.address) f else it }
                else list + f
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning.value = false
            messages.tryEmit("블루투스 검색 실패 (코드 $errorCode)")
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            messages.tryEmit("블루투스를 켜 주세요")
            return
        }
        if (scanning.value) return
        found.value = emptyList()
        scanning.value = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settings, scanCallback) }
            .onFailure { scanning.value = false; messages.tryEmit("검색을 시작할 수 없습니다: ${it.message}") }
        scanStopJob?.cancel()
        scanStopJob = scope.launch { delay(10_000); stopScan() }
    }

    fun stopScan() {
        if (!scanning.value) return
        scanning.value = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    // ---------------------------------------------------------------- 연결

    fun connect(address: String) {
        stopScan()
        disconnect()
        val dev = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        connection.value = Conn.CONNECTING
        connectedAddress.value = address
        gatt = dev.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        timeoutJob = scope.launch {
            delay(15_000)
            if (connection.value == Conn.CONNECTING) {
                messages.tryEmit("연결 시간이 초과되었습니다")
                disconnect()
            }
        }
    }

    fun disconnect() {
        timeoutJob?.cancel(); setupJob?.cancel(); pollJob?.cancel()
        gatt?.let { g -> runCatching { g.disconnect(); g.close() } }
        gatt = null; tx = null; rx = null
        writeAck?.complete(false); descAck?.complete(false); mtuAck?.complete(23)
        infoAck?.complete(null); readyAck?.complete(false); doneAck?.complete(false)
        info.value = null
        setting.value = null
        status.value = Status()
        connection.value = Conn.DISCONNECTED
        connectedAddress.value = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (g !== gatt) return
            if (st == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                scope.launch { delay(300); gatt?.discoverServices() }
            } else {
                scope.launch {
                    val was = connection.value
                    disconnect()
                    if (was == Conn.CONNECTED) messages.tryEmit("기기와 연결이 끊어졌습니다")
                    else if (was == Conn.CONNECTING) messages.tryEmit("연결 실패 (코드 $st)")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            if (g !== gatt) return
            if (st != BluetoothGatt.GATT_SUCCESS) {
                scope.launch { messages.tryEmit("서비스 검색 실패"); disconnect() }
                return
            }
            for (s in g.services) for (c in s.characteristics) {
                val u = c.uuid.toString().lowercase()
                if (u.startsWith(Protocol.TX_PREFIX)) tx = c
                if (u.startsWith(Protocol.RX_PREFIX)) rx = c
            }
            if (tx == null || rx == null) {
                scope.launch { messages.tryEmit("NEJE 각인기가 아닙니다"); disconnect() }
                return
            }
            setupJob = scope.launch { setup() }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            descAck?.complete(st == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, st: Int) {
            mtuAck?.complete(if (st == BluetoothGatt.GATT_SUCCESS) newMtu else 23)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            writeAck?.complete(st == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Android 12 이하")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) onData(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onData(value)
        }
    }

    private suspend fun setup() {
        val g = gatt ?: return
        val rxc = rx ?: return
        gattLock.withLock {
            g.setCharacteristicNotification(rxc, true)
            val cccd = rxc.getDescriptor(CCCD) ?: rxc.descriptors.firstOrNull()
            if (cccd != null) {
                val d = CompletableDeferred<Boolean>().also { descAck = it }
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
                if (started) withTimeoutOrNull(3000) { d.await() }
            }
            val m = CompletableDeferred<Int>().also { mtuAck = it }
            mtu = if (g.requestMtu(Protocol.MTU_REQUEST)) {
                minOf(Protocol.MTU_REQUEST, withTimeoutOrNull(3000) { m.await() } ?: 23)
            } else 23
            Log.d(TAG, "MTU = $mtu")
        }

        var result: DeviceInfo? = null
        for (attempt in 0 until 3) {
            val ia = CompletableDeferred<DeviceInfo?>().also { infoAck = it }
            send(Protocol.VERIFY)
            val r = withTimeoutOrNull(2000) { ia.await() }
            if (r != null) { result = r; break }
            if (ia.isCompleted) break // 응답은 왔지만 지원하지 않는 모델
        }
        if (result == null) {
            if (connection.value == Conn.CONNECTING) {
                messages.tryEmit("기기가 응답하지 않습니다")
                disconnect()
            }
            return
        }
        timeoutJob?.cancel()
        connection.value = Conn.CONNECTED
        messages.tryEmit("${result.model} 연결됨")
        send(Protocol.REQUEST_STATE)
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (connection.value != Conn.CONNECTED || upload.value != Upload.Idle) continue
                send(Protocol.HEARTBEAT)
                if (status.value.state == MachineState.CARVING) send(Protocol.REQUEST_PROGRESS)
            }
        }
    }

    // ---------------------------------------------------------------- 수신

    private fun onData(bytes: ByteArray) {
        val frames = synchronized(parser) { parser.feed(bytes) }
        for (f in frames) {
            val e = Protocol.decode(f) ?: continue
            handle(e)
        }
    }

    private fun handle(e: DeviceEvent) {
        when (e) {
            is DeviceEvent.Info -> {
                if (e.info == null) messages.tryEmit("지원하지 않는 모델입니다 (코드 ${e.modelCode})")
                info.value = e.info
                infoAck?.complete(e.info)
            }
            is DeviceEvent.State -> status.update { it.copy(state = e.state) }
            is DeviceEvent.Settings -> setting.value = e.setting
            is DeviceEvent.UploadReady -> readyAck?.complete(e.ok)
            is DeviceEvent.UploadDone -> doneAck?.complete(e.ok)
            is DeviceEvent.Heartbeat -> {
                val i = info.value
                status.update {
                    it.copy(
                        laserTemp = if (i?.supportsLaserTemp == true) e.laserTemp else null,
                        battery = if (i?.supportsBattery == true) e.battery else null,
                        batteryTemp = if (i?.supportsBattery == true) e.batteryTemp else null,
                    )
                }
            }
            DeviceEvent.Finished -> {
                status.update { it.copy(state = MachineState.IDLE, progress = 100) }
                messages.tryEmit("각인이 완료되었습니다")
            }
            is DeviceEvent.Tilt -> status.update { it.copy(tilt = e.active) }
            is DeviceEvent.Homing -> status.update { it.copy(homing = e.active) }
            DeviceEvent.PowerOff -> messages.tryEmit("기기 전원이 꺼졌습니다")
            is DeviceEvent.Progress -> status.update { it.copy(progress = e.percent, elapsedSec = e.elapsedSec) }
        }
    }

    // ---------------------------------------------------------------- 송신

    suspend fun send(bytes: ByteArray): Boolean = gattLock.withLock { write(bytes) }

    fun sendAsync(bytes: ByteArray) {
        scope.launch { send(bytes) }
    }

    fun updateSetting(s: DeviceSetting) {
        setting.value = s
        sendAsync(Protocol.setting(s))
    }

    private suspend fun write(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = tx ?: return false
        val d = CompletableDeferred<Boolean>().also { writeAck = it }
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, c.writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!started) return false
        return withTimeoutOrNull(2000) { d.await() } ?: false
    }

    /**
     * 이미지 전송. 성공하면 기기가 각인을 시작한다.
     * @param x 기기 좌표계 X (픽셀)
     * @param y 기기 좌표계 Y (픽셀)
     */
    suspend fun uploadImage(img: EncodedImage, x: Int, y: Int): Boolean {
        if (connection.value != Conn.CONNECTED || upload.value != Upload.Idle) return false
        upload.value = Upload.Sending(0f)
        try {
            val done = CompletableDeferred<Boolean>().also { doneAck = it }
            val sent = gattLock.withLock {
                val ready = CompletableDeferred<Boolean>().also { readyAck = it }
                if (!write(Protocol.imageHeader(x, y, img))) {
                    messages.tryEmit("전송 시작 실패")
                    return@withLock false
                }
                when (withTimeoutOrNull(5000) { ready.await() }) {
                    null -> { messages.tryEmit("기기가 응답하지 않습니다"); return@withLock false }
                    false -> { messages.tryEmit("기기가 작업 중입니다. 잠시 후 다시 시도하세요"); return@withLock false }
                    true -> Unit
                }
                val chunk = (mtu - 3).coerceAtLeast(20)
                val gapMs = chunk * 1000L / Protocol.UPLOAD_BYTES_PER_SEC
                var i = 0
                while (i < img.data.size) {
                    val n = minOf(chunk, img.data.size - i)
                    val t0 = SystemClock.elapsedRealtime()
                    if (!write(img.data.copyOfRange(i, i + n))) {
                        messages.tryEmit("데이터 전송 실패")
                        return@withLock false
                    }
                    i += n
                    upload.value = Upload.Sending(i.toFloat() / img.data.size)
                    val left = gapMs - (SystemClock.elapsedRealtime() - t0)
                    if (left > 0) delay(left)
                }
                true
            }
            if (!sent) return false
            upload.value = Upload.Waiting
            val ok = withTimeoutOrNull(20_000) { done.await() } == true
            messages.tryEmit(if (ok) "전송 완료 — 각인을 시작합니다" else "기기가 이미지를 받지 못했습니다")
            if (ok) status.update { it.copy(progress = 0, elapsedSec = 0) }
            return ok
        } finally {
            readyAck = null
            doneAck = null
            upload.value = Upload.Idle
        }
    }

    companion object {
        private const val TAG = "NejeBle"
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
