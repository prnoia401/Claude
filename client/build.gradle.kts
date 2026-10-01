plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Клиент для шлема: принимает команды от Quest Remote на телефоне.
android {
    namespace = "com.prnoia.questclient"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prnoia.questclient"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.$versionCode"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = true
        disable += setOf("HardcodedText", "SetTextI18n", "GradleDependency", "OldTargetApi")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
