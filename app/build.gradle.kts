plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tijack.evo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tijack.evo"
        minSdk = 29
        targetSdk = 35
        versionCode = 29
        versionName = "0.20.1"
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
}

dependencies {
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
}
