import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val releaseKey = Properties().apply {
    val file = rootProject.file("signing/key.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val coreAssets = layout.buildDirectory.dir("generated/folio-assets")
val stageCoreAssets = tasks.register<Sync>("stageCoreAssets") {
    from("src/main/assets")
    into(coreAssets)
    exclude("kokoro/model.onnx", "kokoro/voices.bin", "kokoro/tokens.txt", "kokoro/espeak-ng-data/**",
        "supertonic/*.onnx", "supertonic/voice.bin", "supertonic/unicode_indexer.bin", "supertonic/tts.json")
}
android {
    namespace = "app.folio.reader"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "app.folio.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "0.14.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }
    buildFeatures { compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/pico/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets.getByName("main").assets.setSrcDirs(listOf(coreAssets))
    // Only development debug builds may carry models for offline sample-generation fixtures.
    if (providers.gradleProperty("folioBundledVoices").orNull == "true") sourceSets.getByName("debug").assets.srcDir("src/main/assets")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        // Compress the four CPU runtime variants; Android extracts only the installed ABI.
        jniLibs.useLegacyPackaging = true
        // Preserve the checksum-pinned runtime used by existing voices. A newly
        // installed NDK must not rewrite its ELF padding while packaging Pico.
        jniLibs.keepDebugSymbols += "**/libonnxruntime.so"
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    signingConfigs {
        create("localRelease") {
            if (releaseKey.isNotEmpty()) {
                storeFile = rootProject.file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".debug" }
        getByName("release") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("localRelease")
        }
    }
}
tasks.named("preBuild") { dependsOn(stageCoreAssets) }
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.compose.ui:ui:1.8.2")
    implementation("androidx.compose.foundation:foundation:1.8.2")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jsoup:jsoup:1.18.1") // Already bundled by Readium; expose its parser for verified TOC anchors.
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.1.2")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.1.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
tasks.register("writeRuntimeInventory") {
    doLast {
        val artifacts = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        rootProject.file("runtime-dependencies.txt").writeText(artifacts.map { it.moduleVersion.id.toString() }.distinct().sorted().joinToString("\n"))
    }
}
