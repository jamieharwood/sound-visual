plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// Ubuntu / Linux desktop version: an OpenGL window (LWJGL + GLFW) fed by PipeWire.
val appVersion = "1.0.0"
val lwjglVersion = "3.3.4"
val lwjglNatives = "natives-linux"

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

/**
 * Builds an Ubuntu/Debian installer with its own Java runtime:
 *   ./gradlew :desktop:deb   →   desktop/build/jpackage/sound-visual_<version>_amd64.deb
 * Needs a full JDK (for jpackage) plus dpkg-deb and fakeroot.
 */
val deb by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Packages the desktop app as a .deb (installs to /opt/sound-visual)."
    dependsOn(tasks.named("installDist"))
    val input = layout.buildDirectory.dir("install/sound-visual/lib")
    val dest = layout.buildDirectory.dir("jpackage")
    inputs.dir(input)
    outputs.dir(dest)
    doFirst { delete(dest) }
    val javaHome = System.getProperty("java.home")
    executable = "$javaHome/bin/jpackage"
    args(
        "--type", "deb",
        "--name", "sound-visual",
        "--app-version", appVersion,
        "--description", "Live 3D spectrum of your microphone or what your computer is playing",
        "--vendor", "Jamie Harwood",
        "--input", input.get().asFile.path,
        "--main-jar", "desktop.jar",
        "--main-class", application.mainClass.get(),
        "--java-options", "-Djava.awt.headless=true",
        "--add-modules", "java.base,java.desktop,java.prefs,java.logging,jdk.unsupported",
        "--icon", file("src/main/resources/icon.png").path,
        "--linux-shortcut",
        "--linux-menu-group", "AudioVideo;Audio;",
        "--linux-app-category", "sound",
        "--linux-package-deps", "pipewire-bin",
        "--dest", dest.get().asFile.path,
    )
}
