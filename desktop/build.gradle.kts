plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// Ubuntu / Linux desktop version: an OpenGL window (LWJGL + GLFW) fed by PipeWire.
val appVersion = "1.0.0"
val lwjglVersion = "3.3.4"
// LWJGL's native libraries for the CPU we're building on (the snap builds each arch natively).
val lwjglNatives = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "natives-linux-arm64" else "natives-linux"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    for (module in listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl")) {
        implementation("org.lwjgl:$module")
        runtimeOnly("org.lwjgl:$module::$lwjglNatives")
    }
    implementation("org.joml:joml:1.10.8")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("io.github.jamieharwood.soundvisual.desktop.MainKt")
    applicationName = "sound-visual"
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

tasks.jar {
    manifest { attributes("Implementation-Version" to appVersion) }
}

/** jpackage options shared by the .deb and the app image (used by the snap). */
fun jpackageArgs(input: String, dest: String) = listOf(
    "--name", "sound-visual",
    "--app-version", appVersion,
    "--description", "Live 3D spectrum of your microphone or what your computer is playing",
    "--vendor", "Jamie Harwood",
    "--input", input,
    "--main-jar", "desktop.jar",
    "--main-class", application.mainClass.get(),
    "--java-options", "-Djava.awt.headless=true",
    "--add-modules", "java.base,java.desktop,java.prefs,java.logging,jdk.unsupported",
    "--icon", file("src/main/resources/icon.png").path,
    "--dest", dest,
)

val jpackageTool = "${System.getProperty("java.home")}/bin/jpackage"
val libDir = layout.buildDirectory.dir("install/sound-visual/lib")

/**
 * Builds an Ubuntu/Debian installer with its own Java runtime:
 *   ./gradlew :desktop:deb   →   desktop/build/jpackage/sound-visual_<version>_amd64.deb
 * Needs a full JDK (for jpackage) plus dpkg-deb and fakeroot.
 */
val deb by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Packages the desktop app as a .deb (installs to /opt/sound-visual)."
    dependsOn(tasks.named("installDist"))
    val dest = layout.buildDirectory.dir("jpackage")
    inputs.dir(libDir)
    outputs.dir(dest)
    doFirst { delete(dest) }
    executable = jpackageTool
    args(jpackageArgs(libDir.get().asFile.path, dest.get().asFile.path))
    args(
        "--type", "deb",
        "--linux-shortcut",
        "--linux-menu-group", "AudioVideo;Audio;",
        "--linux-app-category", "sound",
        "--linux-package-deps", "pipewire-bin",
    )
}

/**
 * A self-contained folder (launcher + its own Java runtime), which the snap packages:
 *   ./gradlew :desktop:appImage   →   desktop/build/app-image/sound-visual/bin/sound-visual
 */
val appImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds a self-contained app folder with its own Java runtime."
    dependsOn(tasks.named("installDist"))
    val dest = layout.buildDirectory.dir("app-image")
    inputs.dir(libDir)
    outputs.dir(dest)
    doFirst { delete(dest) }
    executable = jpackageTool
    args(jpackageArgs(libDir.get().asFile.path, dest.get().asFile.path))
    args("--type", "app-image")
}
