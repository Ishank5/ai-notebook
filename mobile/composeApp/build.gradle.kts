import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    // Applied conditionally below: the Google Services plugin hard-fails the build
    // when composeApp/google-services.json is absent. Drop the real file in and it
    // activates automatically on the next build.
    alias(libs.plugins.googleServices) apply false
}

if (project.file("google-services.json").exists()) {
    apply(plugin = libs.plugins.googleServices.get().pluginId)
} else {
    logger.lifecycle(
        "composeApp: google-services.json not found, Firebase plugin skipped. " +
            "Download it from the Firebase Console and place it at composeApp/google-services.json."
    )
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

android {
    namespace = "com.humblesolutions.ainotetaker"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.humblesolutions.ainotetaker"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        // The AI Notebook API (Cloud Function ntApi). Paths such as /api/me are appended to it.
        // Another address is one Gradle property: -PapiBaseUrl=https://...
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"" + (project.findProperty("apiBaseUrl") as String?
                ?: "https://us-central1-team-akshay-sharma.cloudfunctions.net/ntApi") + "\"",
        )
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
    buildFeatures {
        compose = true
        // RecorderSync sends BuildConfig.VERSION_NAME with each recording, and
        // every API call reads BuildConfig.API_BASE_URL.
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.toolingPreview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.navigation.compose)
    // Small settings kept between launches (util/LanguageStore.kt).
    implementation(libs.androidx.datastore.preferences)
    // The recorder: ServiceCompat for a typed foreground service on every API
    // level, and WorkManager for uploads that outlive the app.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.playServices)

    // Firebase — BoM aligns versions.
    // Auth only. Who someone is and everything about their recordings comes
    // from the ntApi HTTP API (see repository/CrmApi.kt).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
}
