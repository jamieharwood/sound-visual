package io.github.jamieharwood.soundvisual.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioBackendTest {
    @Test
    fun pipeWireListsOutputStreamsOncePerApp() {
        val json = """[
          {"type":"PipeWire:Interface:Node","info":{"props":{"media.class":"Audio/Sink","object.serial":108,"node.name":"speakers"}}},
          {"type":"PipeWire:Interface:Node","info":{"props":{"media.class":"Stream/Output/Audio","object.serial":2662,"application.name":"Google Chrome"}}},
          {"type":"PipeWire:Interface:Node","info":{"props":{"media.class":"Stream/Output/Audio","object.serial":2700,"application.name":"Google Chrome"}}},
          {"type":"PipeWire:Interface:Node","info":{"props":{"media.class":"Stream/Output/Audio","object.serial":2800,"application.process.binary":"spotify"}}},
          {"type":"PipeWire:Interface:Link","info":{}}
        ]"""
        val apps = AudioBackend.PipeWire.parseApps(json)
        assertEquals(listOf("Google Chrome" to "2662", "spotify" to "2800"), apps.map { it.appName to it.id })
    }

    @Test
    fun pulseListsSinkInputsByIndex() {
        val json = """[
          {"index":42,"properties":{"application.name":"Firefox","media.name":"Video"}},
          {"index":7,"properties":{"application.name":"Rhythmbox"}}
        ]"""
        val apps = AudioBackend.Pulse.parseApps(json)
        assertEquals(listOf("Firefox" to "42", "Rhythmbox" to "7"), apps.map { it.appName to it.id })
    }

    @Test
    fun pulseCommandsTargetTheRightDevice() {
        // recordCommand needs parec on PATH, so only check the device arguments it adds.
        val cmd = if (AudioBackend.Pulse.available) AudioBackend.Pulse.recordCommand(AudioSource.App("42", "Firefox"), 48_000) else return
        assertEquals("--monitor-stream=42", cmd.last())
    }
}
