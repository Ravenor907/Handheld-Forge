import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The drive engine shared with Raven Disk Utility (github.com/Ravenor907/Raven-Disk-Utility):
// USB mass storage, partition tables, formatting, image writing and the speed test.
// The sources are an exact copy of Raven's core module; don't edit them here.
// Change them in Raven, then run drivecore/sync.sh. CI fails if the copy drifts.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}
