import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

android {
    namespace = "com.flopster101.usbaudiobridge"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.flopster101.usbaudiobridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 1000
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        externalNativeBuild {
            cmake {
                targets.add("gadget_monitor_pause")
                cppFlags("")
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
        
        val gitHash = try {
            val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .directory(project.rootDir)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }.trim().ifBlank { "unknown" }
        } catch (e: Exception) {
            "unknown"
        }
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        
        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86")
            abiFilters.add("x86_64")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            externalNativeBuild {
                cmake {
                    // Apply aggressive native optimization for release-like builds.
                    cFlags += "-O3 -ffast-math"
                    cppFlags += "-O3 -ffast-math"
                }
            }
        }
        create("optimizedDebug") {
            initWith(getByName("release"))
            // Keep release-like optimizations but always sign with debug key for easy installs.
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-optdebug"
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.9.7")
    
    // Required for Theme.Material3.DayNight.NoActionBar in themes.xml
    implementation("com.google.android.material:material:1.13.0")
    
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
