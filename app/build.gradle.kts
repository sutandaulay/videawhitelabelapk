import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    kotlin("android")
    id("com.google.gms.google-services")
}

val localProperties = Properties()
val propsFile = rootProject.file("env.properties")

if (propsFile.exists()) {
    localProperties.load(FileInputStream(propsFile))
}

val FCM_API_URL: String = localProperties.getProperty("FCM_API_URL") ?: ""

android {
    namespace = "com.videa.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.videa.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.1"

        manifestPlaceholders["usesCleartextTraffic"] = "true"
        buildConfigField("String", "FCM_API_URL", "\"$FCM_API_URL\"")
    }

    flavorDimensions += "school"

    productFlavors {
        create("videa") {
            dimension = "school"
            applicationId = "com.videa.app"
            versionCode = 1
            versionName = "1.1"
            buildConfigField("String", "APP_URL", "\"https://videaclass.com/app\"")
            buildConfigField("String", "TENANT_CODE", "\"VIDEA001\"")
            buildConfigField("String", "AUTH_API_URL", "\"https://videaclass.com/api/auth/sign\"")
            resValue("string", "app_name", "Videa Class")
        }

        create("smkannur") {
            dimension = "school"
            applicationId = "com.smkannur.app"
            versionCode = 2
            versionName = "1.2"
            buildConfigField("String", "APP_URL", "\"https://smksannur.sch.id/app\"")
            buildConfigField("String", "TENANT_CODE", "\"2414AE37\"")
            buildConfigField("String", "AUTH_API_URL", "\"https://smkannur.videaclass.com/api/auth/sign\"")
            resValue("string", "app_name", "SMK ANNUR")
        }

        create("smpmuhammadiyah") {
            dimension = "school"
            applicationId = "com.smpmuhpab.app"
            versionCode = 2
            versionName = "1.2"
            buildConfigField("String", "APP_URL", "\"https://smpmuhammadiyahpabuaran.videaclass.com/app\"")
            buildConfigField("String", "TENANT_CODE", "\"590A196F\"")
            buildConfigField("String", "AUTH_API_URL", "\"https://smpmuhammadiyahpabuaran.videaclass.com/api/auth/sign\"")
            resValue("string", "app_name", "SMP MUHAMMADIYAH PABUARAN")
        }

        create("smkdarmawan") {
            dimension = "school"
            applicationId = "com.smkdarmawan.app"
            versionCode = 1
            versionName = "1.1"
            buildConfigField("String", "APP_URL", "\"https://smksdarmawan.videaclass.com/app\"")
            buildConfigField("String", "TENANT_CODE", "\"8321D74C\"")
            buildConfigField("String", "AUTH_API_URL", "\"https://smksdarmawan.videaclass.com/api/auth/sign\"")
            resValue("string", "app_name", "SMK DARMAWAN")
        }

        create("smkpuspitamedika") {
            dimension = "school"
            applicationId = "com.smkpusmed.app"
            versionCode = 1
            versionName = "1.1"
            buildConfigField("String", "APP_URL", "\"https://smkpuspitamedika.videaclass.com/app\"")
            buildConfigField("String", "TENANT_CODE", "\"D9D04E11\"")
            buildConfigField("String", "AUTH_API_URL", "\"https://smkpuspitamedika.videaclass.com/api/auth/sign\"")
            resValue("string", "app_name", "SMK PUSPITA MEDIKA")
        }

        create("smkbudhiwarman") {
            dimension = "school"
            applicationId = "com.smkbudhiwarman.app"
            versionCode = 1
            versionName = "1.1"
            buildConfigField("String", "APP_URL", "\"https://smkbudhiwarman2.videaclass.com/app\"")
            buildConfigField("String", "TENANT_CODE", "\"41C33EFE\"")
            buildConfigField("String", "AUTH_API_URL", "\"https://smkbudhiwarman2.videaclass.com/api/auth/sign\"")
            resValue("string", "app_name", "SMK BUDHI WARMAN 2")
        }

        create("smpmawaddah") {
            dimension = "school"
            applicationId = "com.smpimawaddah.app"
            versionCode = 6
            versionName = "1.6"
            buildConfigField("String", "APP_URL", "\"https://smpmawaddah.videaclass.com/app\"")
            buildConfigField("String", "TENANT_CODE", "\"033B6967\"")
            buildConfigField("String", "AUTH_API_URL", "\"https://smpmawaddah.videaclass.com/api/auth/sign\"")
            resValue("string", "app_name", "SMP MAWADDAH")
        }

        create("mitalqolam") {
            dimension = "school"
            applicationId = "com.mitalqolam.app"
            versionCode = 2
            versionName = "1.2"
            buildConfigField("String", "APP_URL", "\"https://mitalqolam.videaclass.com/app\"")
            buildConfigField("String", "TENANT_CODE", "\"C4E7D243\"")
            buildConfigField("String", "AUTH_API_URL", "\"https://mitalqolam.videaclass.com/api/auth/sign\"")
            resValue("string", "app_name", "MIT Al QOLAM")
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
        debug {
            isMinifyEnabled = false
        }
    }

    applicationVariants.all {
        outputs.all {
            val variantOutput = this as BaseVariantOutputImpl
            val flavor = variantOutput.name // Mengambil nama varian sebagai penanda file
            val version = versionName
            variantOutput.outputFileName = "VIDEA-${flavor}-V${version}.apk"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.airbnb.android:lottie:6.4.1")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("io.coil-kt:coil:2.6.0")
    implementation(platform("com.google.firebase:firebase-bom:34.6.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    val cameraVersion = "1.6.2"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}

tasks.register("assembleDebugUnitTest") {
    description = "Assembles all Debug Unit Tests to resolve variant ambiguity"
    dependsOn("assembleSmkannurDebugUnitTest", "assembleVideaDebugUnitTest")
}