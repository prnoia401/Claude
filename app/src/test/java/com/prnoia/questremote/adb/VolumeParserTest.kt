package com.prnoia.questremote.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeParserTest {

    @Test
    fun parsesVolumeCommand() {
        val out = "[v] will get volume\n[v] volume is 7 in range [0..15]\n"
        assertEquals(Volume(7, 15), VolumeParser.fromVolumeCommand(out))
        assertNull(VolumeParser.fromVolumeCommand("Unknown command: volume"))
    }

    @Test
    fun parsesDumpsysWithStreamVolume() {
        val dump = """
            Stream volumes (device: index)
            - STREAM_VOICE_CALL:
               Muted: false
               Min: 1
               Max: 5
               streamVolume:4
            - STREAM_MUSIC:
               Muted: false
               Min: 0
               Max: 25
               streamVolume:11
               Current: 2 (speaker): 11, 40000000 (default): 5
               Devices: speaker
            - STREAM_ALARM:
               Max: 7
        """.trimIndent()
        assertEquals(Volume(11, 25), VolumeParser.fromDumpsysAudio(dump))
    }

    @Test
    fun parsesDumpsysCurrentOnly() {
        val dump = "- STREAM_MUSIC:\n   Muted: false\n   Min: 0\n   Max: 15\n   Current: 2 (speaker): 9, 4000000 (bt_a2dp): 3\n"
        assertEquals(Volume(9, 15), VolumeParser.fromDumpsysAudio(dump))
        assertNull(VolumeParser.fromDumpsysAudio("nothing here"))
    }
}
