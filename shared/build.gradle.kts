plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Code shared by the phone app and the Wear OS app: spectrum maths, colour palettes,
// the 3D grid renderer and Tilt view's motion control.
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
    testImplementation("junit:junit:4.13.2")
}
