import java.util.Properties
import java.io.FileInputStream

val localProperties = Properties()
val propsFile = rootProject.file("env.properties")

if (propsFile.exists()) {
    localProperties.load(FileInputStream(propsFile))
}

val APP_URL: String = (localProperties.getProperty("APP_URL"))
val TENANT_CODE: String = (localProperties.getProperty("TENANT_CODE"))

val FCM_API_URL: String = (localProperties.getProperty("FCM_API_URL"))

plugins {
    id("com.android.application")
    kotlin("android")
    // Fcm
    id("com.google.gms.google-services")
}

android {
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    namespace = "com.idnoffice.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.idnoffice.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.1"

        // Untuk WebView yang butuh izin internet
        manifestPlaceholders["usesCleartextTraffic"] = "true"

        buildConfigField("String", "APP_URL", "\"$APP_URL\"")
        buildConfigField("String", "TENANT_CODE", "\"$TENANT_CODE\"")
        buildConfigField("String", "FCM_API_URL", "\"$FCM_API_URL\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Pastikan file proguard ada
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // Gunakan Java 17 untuk kompatibilitas plugin modern
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Mencegah build warning dari resource lama
    packaging {
        resources.excludes += listOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/license.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/notice.txt",
            "META-INF/ASL2.0"
        )
    }
}

dependencies {
    // AndroidX core & material
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Animasi Lottie
    implementation("com.airbnb.android:lottie:6.4.1")

    // (Opsional) WebView compat

    implementation("androidx.webkit:webkit:1.10.0")
    // loader image
    implementation("io.coil-kt:coil:2.6.0")

    // Fcm
    implementation(platform("com.google.firebase:firebase-bom:34.6.0"))
    implementation ("com.google.firebase:firebase-messaging")
}
