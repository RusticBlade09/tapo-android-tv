// Windows version. Video = the PC's own VLC (64-bit, must be installed) through vlcj.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
application { mainClass.set("za.munnik.tapoatv.DesktopKt") }

dependencies {
    implementation(project(":core"))
    implementation("org.json:json:20240303")
    implementation("uk.co.caprica:vlcj:4.8.3")
}
