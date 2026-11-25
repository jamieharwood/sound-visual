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

    /** One application's playback stream, captured on its own ([id] is backend-specific). */
    class App(val id: String, val appName: String) : AudioSource(appName, "app") {
        override fun equals(other: Any?) = other is App && other.id == id
        override fun hashCode() = id.hashCode()
    }
}

/**
 * How audio is captured: a command-line recorder that writes raw 48 kHz stereo float32 to
 * stdout, plus a way to list the apps that are playing.
 *
 *  - [PipeWire] (`pw-record` / `pw-dump`) when running normally.
 *  - [Pulse] (`parec` / `pactl`) inside a snap: strict confinement only grants audio through
 *    the PulseAudio protocol (served by pipewire-pulse on modern Ubuntu).
 */
sealed class AudioBackend {
    abstract val available: Boolean
    abstract fun recordCommand(source: AudioSource, rate: Int): List<String>
    abstract fun playingApps(): List<AudioSource.App>

    object PipeWire : AudioBackend() {
        private val recorder by lazy { which("pw-record") }
        private val dumper by lazy { which("pw-dump") }
        override val available get() = recorder != null

        override fun recordCommand(source: AudioSource, rate: Int): List<String> {
            val cmd = mutableListOf(
                recorder!!, "--rate", "$rate", "--channels", "2", "--format", "f32",
                "--latency", "20ms", "--media-role", "Production",
            )
            val props = StringBuilder("{ node.name=sound-visual node.description=\"Sound Visual\"")
            when (source) {
                AudioSource.Microphone -> Unit
                AudioSource.SystemOutput -> props.append(" stream.capture.sink=true")
                is AudioSource.App -> {
                    props.append(" stream.capture.sink=true")
                    cmd += listOf("--target", source.id)
                }
            }
            props.append(" }")
            cmd += listOf("-P", props.toString(), "-")
            return cmd
        }

        override fun playingApps(): List<AudioSource.App> {
            val text = run(dumper ?: return emptyList()) ?: return emptyList()
            return parseApps(text)
        }

        internal fun parseApps(json: String): List<AudioSource.App> {
            val apps = mutableListOf<AudioSource.App>()
            val objects = JSONArray(json)
            for (i in 0 until objects.length()) {
                val obj = objects.optJSONObject(i) ?: continue
                if (obj.optString("type") != "PipeWire:Interface:Node") continue
                val props = obj.optJSONObject("info")?.optJSONObject("props") ?: continue
                if (props.optString("media.class") != "Stream/Output/Audio") continue
                val serial = props.opt("object.serial")?.toString() ?: continue
                apps += AudioSource.App(serial, appName(props))
            }
            return dedupe(apps)
        }
    }

    object Pulse : AudioBackend() {
        private val recorder by lazy { which("parec") }
        private val control by lazy { which("pactl") }
        override val available get() = recorder != null

        override fun recordCommand(source: AudioSource, rate: Int): List<String> {
            val cmd = mutableListOf(
                recorder!!, "--raw", "--format=float32le", "--rate=$rate", "--channels=2",
                "--latency-msec=20", "--client-name=Sound Visual", "--stream-name=Spectrum",
            )
            cmd += when (source) {
                AudioSource.Microphone -> "--device=@DEFAULT_SOURCE@"
                AudioSource.SystemOutput -> "--device=@DEFAULT_MONITOR@"
                is AudioSource.App -> "--monitor-stream=${source.id}"
            }
            return cmd
        }

        override fun playingApps(): List<AudioSource.App> {
            val text = run(control ?: return emptyList(), "-f", "json", "list", "sink-inputs") ?: return emptyList()
            return parseApps(text)
        }

        internal fun parseApps(json: String): List<AudioSource.App> {
            val apps = mutableListOf<AudioSource.App>()
            val inputs = JSONArray(json)
            for (i in 0 until inputs.length()) {
                val obj = inputs.optJSONObject(i) ?: continue
                val index = obj.opt("index")?.toString() ?: continue
                val props = obj.optJSONObject("properties") ?: continue
                apps += AudioSource.App(index, appName(props))
            }
            return dedupe(apps)
        }
    }

    companion object {
        /** Pulse inside a snap (or when PipeWire's tools are missing), otherwise PipeWire. */
        val current: AudioBackend by lazy {
            when {
                System.getenv("SNAP") != null && Pulse.available -> Pulse
                PipeWire.available -> PipeWire
                Pulse.available -> Pulse
                else -> PipeWire // not available: the app falls back to Java Sound for the mic
            }
        }

        internal fun which(name: String): String? =
            (System.getenv("PATH") ?: "/usr/bin:/bin").split(':')
                .map { File(it, name) }
                .firstOrNull { it.canExecute() }
                ?.path

        private fun run(vararg cmd: String): String? = try {
            val proc = ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor(2, TimeUnit.SECONDS)
            text
        } catch (e: Exception) {
            System.err.println("sound-visual: couldn't list apps: $e")
            null
        }

        private fun appName(props: org.json.JSONObject): String =
            props.optString("application.name").ifBlank { null }
                ?: props.optString("node.description").ifBlank { null }
                ?: props.optString("application.process.binary").ifBlank { null }
                ?: props.optString("node.name").ifBlank { "Unknown app" }

        /** One entry per app (its first stream), sorted by name. */
        private fun dedupe(apps: List<AudioSource.App>): List<AudioSource.App> {
            val seen = LinkedHashMap<String, AudioSource.App>()
            for (a in apps) seen.putIfAbsent(a.appName.lowercase(), a)
            return seen.values.sortedBy { it.appName.lowercase() }
        }
    }
}
