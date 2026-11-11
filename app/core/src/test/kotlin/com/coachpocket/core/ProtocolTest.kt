package com.coachpocket.core

import com.coachpocket.core.protocol.PodPacketCodec
import com.coachpocket.core.protocol.PodProtocol
import com.coachpocket.core.protocol.PodStreamDecoder
import com.coachpocket.core.protocol.RawPodPacket
import com.coachpocket.core.protocol.U32Unwrapper
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    private fun packet(seq: Int, t0: Long, n: Int = 19) =
        RawPodPacket(seq, t0, 1000, 3, 3, ShortArray(n * 6) { (it * 37 - 300).toShort() })

    @Test
    fun roundTripKeepsEveryField() {
        val p = packet(65535, 0xFFFF_FFF0L)
        val bytes = PodPacketCodec.encode(p)
        assertEquals(238, bytes.size)
        assertTrue(bytes.size <= PodProtocol.REQUESTED_MTU - 3)
        val d = assertNotNull(PodPacketCodec.decode(bytes))
        assertEquals(65535, d.seq)
        assertEquals(0xFFFF_FFF0L, d.t0Us)
        assertEquals(1000, d.periodUs)
        assertEquals(3, d.accelFs)
        assertEquals(3, d.gyroFs)
        assertContentEquals(p.raw, d.raw)
    }

    @Test
    fun malformedPacketsAreRejected() {
        val good = PodPacketCodec.encode(packet(1, 0))
        assertNull(PodPacketCodec.decode(good.copyOf(good.size - 1)))
        assertNull(PodPacketCodec.decode(ByteArray(5)))
        assertNull(PodPacketCodec.decode(good.clone().also { it[3] = 0x0F })) // version 0
        assertNull(PodPacketCodec.decode(good.clone().also { it[2] = 20 })) // too many samples
    }

    @Test
    fun rawCountsConvertToSiUnits() {
        val raw = ShortArray(6).also { it[2] = 2048; it[3] = 16384 } // 1 g on z at +-16 g, 1000 dps on x
        val d = assertNotNull(PodStreamDecoder().decode(PodPacketCodec.encode(RawPodPacket(0, 5000, 1000, 3, 3, raw))))
        assertEquals(9.80665f, d.samples[0].az, 1e-3f)
        assertEquals(Math.toRadians(1000.0).toFloat(), d.samples[0].gx, 1e-3f)
        assertEquals(5000, d.samples[0].tUs)
    }

    @Test
    fun lossIsCountedAcrossSequenceWrap() {
        val dec = PodStreamDecoder()
        var received = 0
        // 70000 packets cross the 16-bit wrap; drop every 100th and one burst of 5.
        for (i in 0 until 70000) {
            val drop = i % 100 == 99 || i in 30000..30004
            if (drop) continue
            assertNotNull(dec.decode(PodPacketCodec.encode(packet(i and 0xFFFF, (i * 19000L) and 0xFFFFFFFFL))))
            received++
        }
        assertEquals(received.toLong(), dec.packetsReceived)
        assertEquals(70000L - received - 1, dec.packetsLost) // the final dropped packet (index 69999) is unseen
        assertEquals(received * 19L, dec.samplesReceived)
    }

    @Test
    fun duplicateOrLatePacketIsIgnored() {
        val dec = PodStreamDecoder()
        dec.decode(PodPacketCodec.encode(packet(10, 0)))
        dec.decode(PodPacketCodec.encode(packet(11, 19000)))
        assertNull(dec.decode(PodPacketCodec.encode(packet(10, 0))))
        assertEquals(1, dec.packetsLate)
        assertEquals(0, dec.packetsLost)
    }

    @Test
    fun podClockUnwrapsPast32Bits() {
        val u = U32Unwrapper()
        var expected = 4_294_000_000L
        assertEquals(expected, u.unwrap(expected))
        repeat(100) {
            expected += 19_000
            assertEquals(expected, u.unwrap(expected and 0xFFFFFFFFL))
        }
        assertTrue(expected > 0xFFFFFFFFL)
    }

    @Test
    fun syncBeaconRoundTrip() {
        val (t1, t2, t3) = assertNotNull(
            PodPacketCodec.decodeSyncResponse(PodPacketCodec.encodeSyncResponse(9_876_543_210_123L, 4_000_000_001L and 0xFFFFFFFFL, 77)),
        )
        assertEquals(9_876_543_210_123L, t1)
        assertEquals(4_000_000_001L, t2)
        assertEquals(77L, t3)
    }
}
