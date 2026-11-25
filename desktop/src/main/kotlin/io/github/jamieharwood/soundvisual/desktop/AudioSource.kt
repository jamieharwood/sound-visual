package io.github.jamieharwood.soundvisual.desktop

import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/** Where the sound comes from. */
sealed class AudioSource(val label: String, val key: String) {
    /** The default input device (microphone / line in). */
    data object Microphone : AudioSource("Microphone", "mic")

    /** Everything the computer is playing: the default output's monitor. */
    data object SystemOutput : AudioSource("Computer output", "output")

    /** One application's playback stream, captured on its own. */
    class App(val serial: String, val appName: String) : AudioSource(appName, "app") {
        override fun equals(other: Any?) = other is App && other.serial == serial
        override fun hashCode() = serial.hashCode()
    }
}

/** Finds the PipeWire tools and the apps that are playing sound. */
object PipeWire {
    /** `pw-record`, or null if PipeWire's tools aren't installed. */
    val recorder: String? by lazy { which("pw-record") }

    private val dumper: String? by lazy { which("pw-dump") }

    private fun which(name: String): String? =
        (System.getenv("PATH") ?: "/usr/bin:/bin").split(':')
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }
            ?.path

    /** Apps currently playing audio, one entry per app (its first stream). */
    fun playingApps(): List<AudioSource.App> {
        val tool = dumper ?: return emptyList()
        return try {
            val proc = ProcessBuilder(tool).redirectErrorStream(false).start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor(2, TimeUnit.SECONDS)
            parseApps(text)
        } catch (e: Exception) {
            System.err.println("sound-visual: couldn't list apps: $e")
            emptyList()
        }
    }

    internal fun parseApps(json: String): List<AudioSource.App> {
        val seen = LinkedHashMap<String, AudioSource.App>()
        val objects = JSONArray(json)
        for (i in 0 until objects.length()) {
            val obj = objects.optJSONObject(i) ?: continue
            if (obj.optString("type") != "PipeWire:Interface:Node") continue
            val props = obj.optJSONObject("info")?.optJSONObject("props") ?: continue
            if (props.optString("media.class") != "Stream/Output/Audio") continue
            val serial = props.opt("object.serial")?.toString() ?: continue
            val name = props.optString("application.name").ifBlank { null }
                ?: props.optString("node.description").ifBlank { null }
                ?: props.optString("application.process.binary").ifBlank { null }
                ?: props.optString("node.name").ifBlank { "Unknown app" }
            seen.putIfAbsent(name.lowercase(), AudioSource.App(serial, name))
        }
        return seen.values.sortedBy { it.appName.lowercase() }
    }
}
