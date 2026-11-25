pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "SoundVisual"
include(":core", ":desktop")

// The Android apps need the Android SDK; without one (e.g. the snap's build container) only
// the desktop app and its shared core are built.
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")?.trim()
if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":app", ":shared", ":wear")
} else {
    println("Android SDK not found: building :core and :desktop only")
}
