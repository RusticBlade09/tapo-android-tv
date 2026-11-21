// Shared protocol code (camera/hub login, AES stream, control, scan) — plain JVM, used by the TV app and the Windows app.
// kotlin.jvm comes from the same Kotlin plugin jar the root already loads for kotlin.android
plugins { id("org.jetbrains.kotlin.jvm") }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    compileOnly("org.json:json:20240303") // Android ships org.json itself; the desktop app adds this jar
    testImplementation("junit:junit:4.13.2")
}
