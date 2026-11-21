// Windows version: Compose Desktop UI; video decoded by FFmpeg packed inside the app (JavaCV, LGPL build) — nothing to install.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
compose.desktop { application { mainClass = "za.munnik.tapoatv.DesktopKt" } }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation("org.json:json:20240303")
    // Only FFmpegFrameGrabber is used: javacv without its OpenCV/camera extras, FFmpeg natives for 64-bit Windows only.
    implementation("org.bytedeco:javacv:1.5.10") { isTransitive = false }
    implementation("org.bytedeco:javacpp:1.5.10")
    implementation("org.bytedeco:javacpp:1.5.10:windows-x86_64")
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10")
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10:windows-x86_64")
    testImplementation("junit:junit:4.13.2")
}
