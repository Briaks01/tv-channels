plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.home.tvchannels"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.home.tvchannels"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // тестовая версия с логами самопроверки (tools/selftest.py); ставится поверх релиза
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    splits {
        abi { isEnable = false }
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
    // в android.jar org.json — заглушки; для JVM-тестов нужна настоящая библиотека
    testImplementation("org.json:json:20240303")
}
