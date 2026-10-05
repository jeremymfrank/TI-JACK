package com.tijack.evo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NspireProtocolTest {
    @Test
    fun documentedChecksumVectorsMatch() {
        assertEquals(0xFF00, NspireProtocol.dataChecksum(byteArrayOf(0x00, 0xFF.toByte())))
        assertEquals(0x57AD, NspireProtocol.dataChecksum(byteArrayOf(0x05, 0x00, 0x00)))
        assertEquals(
            0xA095,
            NspireProtocol.dataChecksum(
                byteArrayOf(
                    0x20, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x05, 0x01, 0x00
                )
            )
        )
    }

    @Test
    fun directoryRootIsPaddedBeforeNull() {
        val command = NspireProtocol.dirStart("/")
        assertEquals(10, command.size)
        assertEquals(0x0D, command[0].toInt() and 0xFF)
        assertEquals('/'.code, command[1].toInt() and 0xFF)
        assertEquals(0, command.last().toInt())
    }

    @Test
    fun putFileUsesBigEndianSize() {
        val command = NspireProtocol.putFile("/Examples/a.tns", 0x01020304)
        assertEquals(0x03, command[0].toInt() and 0xFF)
        assertEquals(0x01, command[1].toInt() and 0xFF)
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04),
            command.copyOfRange(command.size - 4, command.size)
        )
    }

    @Test
    fun fileChunkEnforcesProtocolPayloadLimit() {
        assertEquals(254, NspireProtocol.fileChunk(ByteArray(253)).size)
        var failed = false
        try {
            NspireProtocol.fileChunk(ByteArray(254))
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun parseDirectoryEntry() {
        val name = "Test.tns".toByteArray()
        val data = ByteArray(3 + name.size + 1 + 4 + 4 + 2)
        data[0] = 0x10
        data[1] = 0
        data[2] = (data.size - 3).toByte()
        name.copyInto(data, 3)
        var p = 3 + name.size + 1
        data[p++] = 0
        data[p++] = 0
        data[p++] = 0x12
        data[p++] = 0x34
        data[p++] = 0
        data[p++] = 0
        data[p++] = 0
        data[p++] = 0x09
        data[p++] = 0
        data[p] = 0

        val entry = requireNotNull(NspireProtocol.parseDirectoryEntry(data))
        assertEquals("Test.tns", entry.name)
        assertEquals(0x1234, entry.size)
        assertEquals(9, entry.dateRaw)
        assertFalse(entry.directory)
        assertTrue(NspireProtocol.isSuccess(byteArrayOf(0xFF.toByte(), 0x00)))
        assertEquals(0x0A, NspireProtocol.failureCode(byteArrayOf(0xFF.toByte(), 0x0A)))
    }
}
