import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinCocoapods)
    alias(libs.plugins.sqldelight)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    cocoapods {
        version = "1.0"
        summary = "Shared Kotlin module for Humble Campaign"
        homepage = "https://humblesolutions.com"
        ios.deploymentTarget = "15.0"
        podfile = project.file("../iosApp/Podfile")
        framework {
            baseName = "shared"
            isStatic = true
        }
        // Firebase pods are declared in iosApp/Podfile, NOT here.
        // shared/ stays pure Kotlin — Firebase lives in platform repository impls.
    }

    android {
       namespace = "com.humblesolutions.ainotetaker.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()

       compilerOptions {
           jvmTarget = JvmTarget.JVM_17
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.runtime)
            // reading the CRM API's meetings JSON once for both phones (meeting/ApiMeetingRepository.kt).
            // The JSON tree only, so no serialization compiler plugin.
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.sqldelight.androidDriver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.nativeDriver)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // Use cases are `suspend`; `runTest` is how commonTest drives one
            // without a platform-specific blocking runner.
            implementation(libs.kotlinx.coroutines.test)
        }
        // The host test runs on the JVM, where the Android driver cannot open a
        // database — it needs a real Android runtime. JDBC is how a common test
        // gets a driver on this side of the hierarchy.
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqliteDriver)
        }
    }
}

sqldelight {
    databases {
        /*
         * THE RECORDER'S QUEUE, IN ITS OWN FOLDER AND ITS OWN FILE. Setting
         * `srcDirs` is what keeps the two apart: a database with none reads
         * every `src/<set>/sqldelight` folder, so without it this schema would
         * be compiled into the roll as well. The roll is shipped and replaced
         * wholesale on update; recordings are the user's own data and must
         * never be swept away with it.
         */
        create("RecordingDatabase") {
            packageName.set("com.humblesolutions.ainotetaker.recording.db")
            srcDirs("src/commonMain/sqldelight-recording")
        }
    }
}
