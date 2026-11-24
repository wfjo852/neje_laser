package com.nejestudio.ble

/**
 * NEJE 레이저 각인기 BLE 프로토콜.
 * 상세 설명: docs/PROTOCOL.md
 *
 * 프레임: FF AA LEN TYPE ... 55  (LEN = 전체 길이, 정수는 빅엔디언)
 */
object Protocol {
    const val DEVICE_NAME = "Laser Carver"
    const val TX_PREFIX = "0000ffe9"
    const val RX_PREFIX = "0000ffea"
    const val MTU_REQUEST = 53
    /** 원본 앱이 사용하는 업로드 속도 제한 (바이트/초) */
    const val UPLOAD_BYTES_PER_SEC = 10240

    const val FRAME_MODE_STOP = 0
    const val FRAME_MODE_PREVIEW = 2

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    val VERIFY = bytes(0xFF, 0xAA, 0x08, 0x01, 0x01, 0x5A, 0xA5, 0x55)
    val PAUSE = bytes(0xFF, 0xAA, 0x08, 0x02, 0x01, 0x01, 0x00, 0x55)
    val RESUME = bytes(0xFF, 0xAA, 0x08, 0x02, 0x01, 0x01, 0x01, 0x55)
    val STOP = bytes(0xFF, 0xAA, 0x08, 0x02, 0x01, 0x01, 0x02, 0x55)
    val REQUEST_STATE = bytes(0xFF, 0xAA, 0x08, 0x02, 0x01, 0x00, 0x01, 0x55)
    val HEARTBEAT = bytes(0xFF, 0xAA, 0x07, 0x0B, 0x01, 0x00, 0x55)
    val REQUEST_PROGRESS = bytes(0xFF, 0xAA, 0x07, 0x0D, 0x01, 0x00, 0x55)
    val RESET_TILT = bytes(0xFF, 0xAA, 0x08, 0x0C, 0x01, 0x03, 0x00, 0x55)

    fun setting(s: DeviceSetting) = bytes(
        0xFF, 0xAA, 0x0B, 0x03, 0x01,
        s.carvingTime, s.power, s.weakLevel, s.senseLevel, s.fastMode,
        0x55,
    )

    /** 약광으로 작업 영역 테두리를 표시 (mode 2 = 시작, 0 = 종료). 단위: 기기 픽셀 */
    fun frame(mode: Int, x: Int, y: Int, w: Int, h: Int) = bytes(
        0xFF, 0xAA, 0x10, 0x05, 0x01, 'P'.code, mode,
        x shr 8, x, y shr 8, y, w shr 8, w, h shr 8, h,
        0x55,
    )

    fun imageHeader(x: Int, y: Int, img: EncodedImage): ByteArray {
        val wPad = img.rowBytes * 8
        val len = img.data.size
        return bytes(
            0xFF, 0xAA, 0x16, 0x04, 0x02, 0x01, 'P'.code,
            x shr 8, x, y shr 8, y,
            wPad shr 8, wPad, img.height shr 8, img.height,
            len ushr 24, len shr 16, len shr 8, len,
            img.width shr 8, img.width,
            0x55,
        )
    }

    /**
     * 1비트 비트맵 인코딩. mask[y*width+x] == true 이면 각인(검정).
     * 짝수 줄은 MSB부터 정방향, 홀수 줄은 줄 전체를 비트 단위로 좌우 반전(지그재그 주사).
     */
    fun encodeImage(mask: BooleanArray, width: Int, height: Int): EncodedImage {
        require(mask.size == width * height)
        val rb = (width + 7) / 8
        val data = ByteArray(rb * height)
        for (y in 0 until height) {
            val even = y % 2 == 0
            for (x in 0 until width) {
                if (!mask[y * width + x]) continue
                if (even) {
                    val i = y * rb + x / 8
                    data[i] = (data[i].toInt() or (0x80 ushr (x % 8))).toByte()
                } else {
                    val i = (y + 1) * rb - x / 8 - 1
                    data[i] = (data[i].toInt() or (1 shl (x % 8))).toByte()
                }
            }
        }
        return EncodedImage(width, height, rb, data)
    }

    private val MODELS = mapOf(
        21 to "BLE 2019", 23 to "Master 2", 24 to "Master 2 Plus", 25 to "Master 2 Max",
        26 to "Master 2 mini", 27 to "Master 2S", 28 to "Master 2S Plus",
        29 to "Master 2S Max", 30 to "Master 2S Mix",
    )

    fun decode(f: IntArray): DeviceEvent? {
        fun at(i: Int) = f.getOrElse(i) { 0 }
        fun u16(i: Int) = (at(i) shl 8) or at(i + 1)
        fun yes(i: Int) = at(i) == 'Y'.code
        return when (at(3)) {
            1 -> {
                val code = at(5)
                val model = if (at(6) == 1) MODELS[code] else null
                val info = model?.let {
                    DeviceInfo(
                        model = it,
                        protocolVersion = at(4),
                        firmware = at(7),
                        maxWidth = u16(8),
                        maxHeight = u16(10),
                        mmPerPixel = at(16) / 1000f,
                        supportsBattery = yes(28),
                        supportsLaserTemp = yes(29),
                        supportsPwm = yes(30),
                        supportsTilt = yes(31),
                        supportsWeakLevel = yes(32),
                        supportsFastMode = yes(33),
                    )
                }
                DeviceEvent.Info(info, code)
            }
            2 -> if (at(5) == 1) {
                when (at(6)) {
                    0 -> DeviceEvent.State(MachineState.IDLE)
                    2 -> DeviceEvent.State(MachineState.PAUSED)
                    80, 71 -> DeviceEvent.State(MachineState.CARVING)
                    else -> null
                }
            } else null
            3 -> DeviceEvent.Settings(DeviceSetting(at(5), at(6), at(7), at(8), at(9)))
            4 -> when (at(5)) {
                2 -> DeviceEvent.UploadReady(at(6) == 1)
                4 -> DeviceEvent.UploadDone(at(6) == 1)
                else -> null
            }
            11 -> DeviceEvent.Heartbeat(laserTemp = at(5), battery = at(6), batteryTemp = at(9))
            12 -> when (at(5)) {
                1 -> DeviceEvent.Finished
                3 -> DeviceEvent.Tilt(at(6) == 1)
                4 -> DeviceEvent.Homing(at(6) == 1)
                5 -> if (at(6) == 0) DeviceEvent.PowerOff else null
                else -> null
            }
            13 -> if (at(5) == 37) DeviceEvent.Progress(at(6), u16(7)) else null
            else -> null
        }
    }
}

class EncodedImage(val width: Int, val height: Int, val rowBytes: Int, val data: ByteArray)

data class DeviceSetting(
    val carvingTime: Int = 0,
    val power: Int = 100,
    val weakLevel: Int = 0,
    val senseLevel: Int = 0,
    val fastMode: Int = 0,
)

data class DeviceInfo(
    val model: String,
    val protocolVersion: Int,
    val firmware: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    val mmPerPixel: Float,
    val supportsBattery: Boolean,
    val supportsLaserTemp: Boolean,
    val supportsPwm: Boolean,
    val supportsTilt: Boolean,
    val supportsWeakLevel: Boolean,
    val supportsFastMode: Boolean,
)

enum class MachineState(val label: String) {
    UNKNOWN("-"), IDLE("대기"), PAUSED("일시정지"), CARVING("각인 중")
}

sealed interface DeviceEvent {
    data class Info(val info: DeviceInfo?, val modelCode: Int) : DeviceEvent
    data class State(val state: MachineState) : DeviceEvent
    data class Settings(val setting: DeviceSetting) : DeviceEvent
    data class UploadReady(val ok: Boolean) : DeviceEvent
    data class UploadDone(val ok: Boolean) : DeviceEvent
    data class Heartbeat(val laserTemp: Int, val battery: Int, val batteryTemp: Int) : DeviceEvent
    data object Finished : DeviceEvent
    data class Tilt(val active: Boolean) : DeviceEvent
    data class Homing(val active: Boolean) : DeviceEvent
    data object PowerOff : DeviceEvent
    data class Progress(val percent: Int, val elapsedSec: Int) : DeviceEvent
}

/** 수신 바이트 스트림을 프레임 단위로 잘라낸다. */
class FrameParser {
    private val buf = ArrayList<Int>()

    fun feed(bytes: ByteArray): List<IntArray> {
        bytes.forEach { buf.add(it.toInt() and 0xFF) }
        val out = mutableListOf<IntArray>()
        while (true) {
            val start = buf.indexOf(0xFF)
            if (start < 0) { buf.clear(); break }
            if (start > 0) buf.subList(0, start).clear()
            if (buf.size < 3) break
            if (buf[1] != 0xAA) { buf.removeAt(0); continue }
            val len = buf[2]
            if (len < 4) { buf.removeAt(0); continue }
            if (buf.size < len) break
            if (buf[len - 1] != 0x55) { buf.removeAt(0); continue }
            out += IntArray(len) { buf[it] }
            buf.subList(0, len).clear()
        }
        return out
    }
}
