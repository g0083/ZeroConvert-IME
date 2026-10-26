import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sharedHostAssets by tasks.registering(Sync::class) {
    from("../app/src/main/assets") { exclude("dictionary/reverse-v2.bin") }
    into(layout.buildDirectory.dir("sharedHostAssets"))
}

android {
    namespace = "com.zeroconvert.ime"
    compileSdk = 34
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.zeroconvert.ime"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "2.0.5-dev"
        buildConfigField("boolean", "WINDOWS_ENGINE", "true")

        testInstrumentationRunner = "com.zeroconvert.ime.PortInstrumentationRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../app/proguard-rules.pro"
            )
        }
    }

    // Every Windows update still builds/checks both ABIs. Each installable APK
    // carries only the target device's native runtime instead of both copies.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = providers.gradleProperty("universalApk").orNull == "true"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../app/src/main/AndroidManifest.xml")
            java.srcDir("../app/src/main/java")
            res.srcDir("../app/src/main/res")
            assets.srcDirs(sharedHostAssets.map { it.destinationDir }, "../../engine/build/assets")
            jniLibs.srcDir("../../engine/build/jniLibs")
        }
        getByName("debug").java.srcDir("../app/src/debug/java")
        getByName("debug").manifest.srcFile("../app/src/debug/AndroidManifest.xml")
        getByName("androidTest").java.srcDir("../app/src/androidTest/java")
        getByName("androidTest").assets.srcDir("../app/src/androidTest/assets")
        getByName("test").java.apply {
            srcDir("../app/src/test/java")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        // Android extracts these once at installation; inference maps the same
        // native code. This reduces download size, not installed library size.
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    testImplementation("junit:junit:4.13.2")
}

val verifyAssistAssets by tasks.registering {
    val corrector = layout.projectDirectory.file("../app/src/main/assets/models/zerocon-model-corrector.gguf")
    val reverse = layout.projectDirectory.file("../app/src/main/assets/dictionary/reverse-v2.bin")
    val catalog = rootProject.file("../windows/deployment/model-catalog.json")
    inputs.files(corrector, reverse, catalog)
    doLast {
        check(corrector.asFile.isFile && reverse.asFile.isFile) {
            "Run scripts/prepare-android-assist-assets.ps1 before building Android."
        }
        val models = (groovy.json.JsonSlurper().parse(catalog) as Map<*, *>)["models"] as List<*>
        val expected = models.map { it as Map<*, *> }.single { it["id"] == "corrector" }["sha256"]
        val digest = MessageDigest.getInstance("SHA-256")
        corrector.asFile.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "Corrector SHA-256 mismatch" }
        reverse.asFile.inputStream().use { input ->
            val magic = ByteArray(4)
            check(input.read(magic) == 4 && magic.contentEquals("AZR2".toByteArray())) { "Invalid reverse dictionary" }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyAssistAssets, sharedHostAssets) }

val verifySharedEngine by tasks.registering {
    doLast {
        exec {
            workingDir(rootProject.file(".."))
            commandLine("node", "scripts/check-shared-engine.mjs")
        }
        val nativeRoot = rootProject.file("../engine/build/jniLibs")
        for (abi in listOf("arm64-v8a", "x86_64")) {
            for (library in listOf("libzenz_jni.so", "libzerocon_swift.so")) {
                check(nativeRoot.resolve("$abi/$library").isFile) {
                    "Build the Windows shared engine for $abi with scripts/build-android-shared-engine.ps1 first."
                }
            }
        }
        check(rootProject.file("../engine/build/assets/shared-engine/sources.json").isFile) { "Missing shared source manifest." }
    }
}
tasks.named("preBuild").configure { dependsOn(verifySharedEngine) }

// Incremental ZIP updates can leave obsolete native bytes in a debug APK after
// repeated large library changes. Keep compilation incremental, but repack the
// small set of final artifacts from current inputs each time.
tasks.matching { it.name == "packageDebug" || it.name == "packageRelease" }.configureEach {
    outputs.upToDateWhen { false }
}

tasks.withType<Test>().configureEach {
    filter {
        includeTestsMatching("com.zeroconvert.ime.SharedEngineInputTest")
        includeTestsMatching("com.zeroconvert.ime.ComposingTextTest")
        includeTestsMatching("com.zeroconvert.ime.FlickInputAndModifierTest")
        includeTestsMatching("com.zeroconvert.ime.KeyboardShiftStateTest")
        includeTestsMatching("com.zeroconvert.ime.ComposingEditorStateTest")
        includeTestsMatching("com.zeroconvert.ime.EditorContextTest")
        includeTestsMatching("com.zeroconvert.ime.SelectionCharacterCountTest")
    }
}
