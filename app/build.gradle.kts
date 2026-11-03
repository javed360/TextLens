plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.textlens.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.textlens.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Size: phones are ARM. Dropping x86/x86_64 native libs (Tesseract, ML Kit, ...) removes
        // roughly half of the APK. Add "x86_64" back only if you test on an emulator.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        // Size: keep only the languages this app actually uses from Material/AndroidX/ML Kit strings.
        resourceConfigurations += listOf("en", "bn", "ar")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
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
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Compress native libraries inside the APK (smaller download/APK file).
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // OCR - supports Bengali, English, Arabic via tessdata files
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")

    // Offline translation (Bengali, English, Arabic all supported)
    implementation("com.google.mlkit:translate:17.0.3")

    // CameraX for the in-app camera capture feature
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // PDF text extraction
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Networking for downloading OCR language data on demand
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
