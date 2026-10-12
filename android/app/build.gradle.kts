import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Copies the catalog and publisher keys shared with the desktop app into the APK's assets. */
abstract class SyncCatalog : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val catalogRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun run() {
        val root = catalogRoot.get().asFile
        val out = output.get().asFile
        out.deleteRecursively()
        sources.files.forEach { f ->
            val dest = out.resolve(f.relativeTo(root).path)
            dest.parentFile.mkdirs()
            f.copyTo(dest, overwrite = true)
        }
    }
}

val shared = rootProject.layout.projectDirectory.dir("../handheld_forge")
val syncCatalog = tasks.register<SyncCatalog>("syncCatalog") {
    catalogRoot.set(shared)
    sources.from(shared.file("distros.json"), shared.dir("keys").asFileTree.matching { include("*.pub") })
}

// Signing: a release key from CI secrets if configured, otherwise the repo's
// shared test key (so test builds still install over each other).
val keystoreFile = System.getenv("HF_KEYSTORE_FILE")?.let { file(it) } ?: rootProject.file("signing/test-key.p12")
val keystorePass = System.getenv("HF_KEYSTORE_PASSWORD") ?: "handheld-forge-test"
val keyAliasName = System.getenv("HF_KEY_ALIAS") ?: "handheld-forge"

android {
    namespace = "io.github.ravenor907.handheldforge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ravenor907.handheldforge"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        create("forge") {
            storeFile = keystoreFile
            storePassword = keystorePass
            keyAlias = keyAliasName
            keyPassword = keystorePass
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("forge")
        }
        debug {
            signingConfig = signingConfigs.getByName("forge")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncCatalog, SyncCatalog::output)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.apache.commons:commons-compress:1.27.1") // bzip2 for SteamOS images

    testImplementation("junit:junit:4.13.2")
}
