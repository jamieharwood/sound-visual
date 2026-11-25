plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Platform-neutral code shared by the phone, watch and desktop apps: spectrum maths,
// colour palettes, the feed interface the renderers draw and the watch protocol.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
