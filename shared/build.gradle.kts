plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Android code shared by the phone app and the Wear OS app: the 3D grid renderer and Tilt
// view's motion control. Platform-neutral code (spectrum maths, palettes) lives in :core.
android {
    namespace = "io.github.jamieharwood.soundvisual.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core"))
}
