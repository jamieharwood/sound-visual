plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.jamieharwood.soundvisual"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jamieharwood.soundvisual"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the debug key so the release APK installs out of the box.
            // Replace with your own signingConfig before publishing to a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":shared"))
    // Wearable Data Layer: streams the graph to the Wear OS companion.
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    // ARCore: tracks the phone's real position for Tilt view's push/pull zoom.
    implementation("com.google.ar:core:1.56.0")
    testImplementation("junit:junit:4.13.2")
}
