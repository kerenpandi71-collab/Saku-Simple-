plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keren.sakusimple"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.keren.sakusimple"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    flavorDimensions += "app"

    productFlavors {
        create("virtualDisplayTouch") {
            dimension = "app"
            applicationId = "com.keren.sakusimple.virtualdisplaytouch"
        }
        create("standaloneGamepad") {
            dimension = "app"
            applicationId = "com.keren.sakugamepad"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        aidl = true
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
