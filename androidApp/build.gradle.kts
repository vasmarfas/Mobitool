import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val googleServicesFile = file("google-services.json")

if (googleServicesFile.exists()) {
    apply(plugin = libs.plugins.googleServices.get().pluginId)
    apply(plugin = libs.plugins.crashlytics.get().pluginId)
    apply(plugin = libs.plugins.firebasePerf.get().pluginId)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.filekit.dialogs)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

}

android {
    namespace = "com.vasmarfas.card"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.vasmarfas.mobitool"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        create("release") {
            storeFile = System.getenv("KEYSTORE_PATH")?.let { file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // release with R8 on, signed with the debug key so profiling runs need no upload keystore
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}
