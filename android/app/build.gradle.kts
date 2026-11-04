import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val bundledModelAssets by tasks.registering(Sync::class) {
    from("src/main/assets") {
        exclude { it.file.extension == "gguf" && it.file.name !in setOf(
            "zerocon-nano-v4-iq4nl-emb8.gguf", "zerocon-nano-v4-q8_0.gguf"
        ) }
    }
    into(layout.buildDirectory.dir("bundledModelAssets"))
}

android {
    namespace = "com.zeroconvert.ime"
    compileSdk = 34
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.zeroconvert.ime"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("boolean", "WINDOWS_ENGINE", "false")

        testInstrumentationRunner = "com.zeroconvert.ime.PortInstrumentationRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                cFlags += "-O3"
                cppFlags += "-std=c++17 -O3"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DGGML_OPENMP=OFF"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets.getByName("main").assets.setSrcDirs(listOf(bundledModelAssets.map { it.destinationDir }))

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
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
    val corrector = layout.projectDirectory.file("src/main/assets/models/zerocon-model-corrector.gguf")
    val reverse = layout.projectDirectory.file("src/main/assets/dictionary/reverse-v2.bin")
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
tasks.named("preBuild").configure { dependsOn(verifyAssistAssets) }

tasks.named("preBuild").configure { dependsOn(bundledModelAssets) }
apply(from = rootProject.file("verify-conversion-models.gradle"))
