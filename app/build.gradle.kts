plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.smpybi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smpybi.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 4
        versionName = "1.4"

        // Untuk WebView yang butuh izin internet
        manifestPlaceholders["usesCleartextTraffic"] = "true"
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

    // Animasi Lottie
    implementation("com.airbnb.android:lottie:6.4.1")

    // (Opsional) WebView compat
    implementation("androidx.webkit:webkit:1.10.0")
}
