plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Wear OS companion: mirrors the phone app's graph (see WearProtocol), with wrist tilt and
// crown zoom. Same applicationId and signing key as the phone app, which the Wearable Data
// Layer requires for the two to talk.
android {
    namespace = "io.github.jamieharwood.soundvisual.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jamieharwood.soundvisual"
        minSdk = 30 // Wear OS 3
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
}
