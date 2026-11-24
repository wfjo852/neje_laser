package com.nejestudio.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    /** 원본 앱(PictureSender.Launch)의 인코딩을 그대로 옮긴 참조 구현 */
    private fun reference(mask: BooleanArray, width: Int, height: Int): ByteArray {
        val i2 = if (width % 8 == 0) width / 8 else width / 8 + 1
        val out = ByteArray(i2 * height)
        for (y in 0 until height) for (x in 0 until width) {
            if (!mask[y * width + x]) continue
            if (y % 2 == 0) {
                val i = y * i2 + x / 8
                out[i] = ((128 shr (x % 8)).toByte().toInt() or out[i].toInt()).toByte()
            } else {
                val i = (y + 1) * i2 - x / 8 - 1
                out[i] = ((1 shl (x % 8)).toByte().toInt() or out[i].toInt()).toByte()
            }
        }
        return out
    }

    @Test
    fun encodeMatchesOriginalApp() {
        val w = 13; val h = 5
        val mask = BooleanArray(w * h) { (it * 7 + it / 3) % 5 < 2 }
        val enc = Protocol.encodeImage(mask, w, h)
        assertEquals(2, enc.rowBytes)
        assertArrayEquals(reference(mask, w, h), enc.data)
    }

    @Test
    fun encodeSerpentine() {
        // 1px 가로선: 첫 픽셀만 검정
        val mask = BooleanArray(16 * 2).also { it[0] = true; it[16] = true }
        val d = Protocol.encodeImage(mask, 16, 2).data
        assertEquals(0x80.toByte(), d[0])      // 짝수 줄: 첫 바이트 MSB
        assertEquals(0x01.toByte(), d[3])      // 홀수 줄: 마지막 바이트 LSB
    }

    @Test
    fun headerLayout() {
        val img = EncodedImage(width = 13, height = 300, rowBytes = 2, data = ByteArray(600))
        val h = Protocol.imageHeader(0x0102, 0x0304, img)
        assertEquals(22, h.size)
        assertEquals(0x16.toByte(), h[2])
        assertEquals('P'.code.toByte(), h[6])
        assertEquals(0x01.toByte(), h[7]); assertEquals(0x02.toByte(), h[8])
        assertEquals(16.toByte(), h[12])                       // 8의 배수로 맞춘 폭
        assertEquals(0x01.toByte(), h[13]); assertEquals(0x2C.toByte(), h[14]) // 300
        assertEquals(0x02.toByte(), h[17]); assertEquals(0x58.toByte(), h[18]) // 600
        assertEquals(13.toByte(), h[20])
        assertEquals(0x55.toByte(), h[21])
    }

    @Test
    fun parserHandlesSplitAndNoise() {
        val p = FrameParser()
        val hb = byteArrayOf(0xFF.toByte(), 0xAA.toByte(), 10, 11, 1, 35, 80, 0, 0, 0x55)
        assertTrue(p.feed(byteArrayOf(0x12, 0x34) + hb.copyOfRange(0, 4)).isEmpty())
        val frames = p.feed(hb.copyOfRange(4, hb.size) + hb)
        assertEquals(2, frames.size)
        val ev = Protocol.decode(frames[0]) as DeviceEvent.Heartbeat
        assertEquals(35, ev.laserTemp)
        assertEquals(80, ev.battery)
    }

    @Test
    fun decodeDeviceInfo() {
        val f = IntArray(37)
        f[0] = 0xFF; f[1] = 0xAA; f[2] = 37; f[3] = 1; f[4] = 3
        f[5] = 27; f[6] = 1; f[7] = 5
        f[8] = 0x02; f[9] = 0x26   // 550
        f[10] = 0x02; f[11] = 0x26
        f[16] = 75
        for (i in 26..33) f[i] = 'Y'.code
        f[36] = 0x55
        val info = (Protocol.decode(f) as DeviceEvent.Info).info!!
        assertEquals("Master 2S", info.model)
        assertEquals(550, info.maxWidth)
        assertEquals(0.075f, info.mmPerPixel, 1e-6f)
        assertTrue(info.supportsPwm)
    }
}
