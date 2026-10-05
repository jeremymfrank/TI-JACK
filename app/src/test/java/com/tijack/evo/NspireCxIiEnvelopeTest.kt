package com.tijack.evo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NspireCxIiEnvelopeTest {
    @Test
    fun streamFrameRoundTripsAndChecksumValidates() {
        val payload = byteArrayOf(0x54, 0xFD.toByte(), 0x12, 0x34, 0x00)
        val encoded = NspireCxIiEnvelope.encode(
            service = NspireCxIiEnvelope.SERVICE_STREAM,
            sequence = 0x1234,
            payload = payload
        )

        assertEquals(NspireCxIiEnvelope.HEADER_SIZE + payload.size, encoded.size)
        assertTrue(NspireCxIiEnvelope.isChecksumValid(encoded))

        val frame = NspireCxIiEnvelope.decode(encoded)
        assertEquals(NspireCxIiEnvelope.SERVICE_STREAM, frame.service)
        assertEquals(NspireCxIiEnvelope.ADDRESS_HOST, frame.source)
        assertEquals(NspireCxIiEnvelope.ADDRESS_CALCULATOR, frame.destination)
        assertEquals(0x1234, frame.sequence)
        assertArrayEquals(payload, frame.payload)
        assertFalse(frame.isAck)
    }

    @Test
    fun ackPreservesSequenceAndReversesAddresses() {
        val request = NspireCxIiEnvelope.decode(
            NspireCxIiEnvelope.encode(
                service = NspireCxIiEnvelope.SERVICE_TIME,
                sequence = 7,
                source = NspireCxIiEnvelope.ADDRESS_CALCULATOR,
                destination = NspireCxIiEnvelope.ADDRESS_HOST,
                payload = byteArrayOf(0)
            )
        )

        val ack = NspireCxIiEnvelope.decode(NspireCxIiEnvelope.ackFor(request))
        assertTrue(ack.isAck)
        assertEquals(NspireCxIiEnvelope.SERVICE_TIME, ack.baseService)
        assertEquals(7, ack.sequence)
        assertEquals(NspireCxIiEnvelope.ADDRESS_HOST, ack.source)
        assertEquals(NspireCxIiEnvelope.ADDRESS_CALCULATOR, ack.destination)
        assertEquals(0, ack.requestAck and 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptedPacketFailsDecode() {
        val packet = NspireCxIiEnvelope.encode(
            service = NspireCxIiEnvelope.SERVICE_STREAM,
            sequence = 1,
            payload = byteArrayOf(1, 2, 3)
        )
        packet[packet.lastIndex] = (packet.last().toInt() xor 0x40).toByte()
        NspireCxIiEnvelope.decode(packet)
    }
}
