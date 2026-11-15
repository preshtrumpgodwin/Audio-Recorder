plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.audiolab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.audiolab"
        minSdk = 29          // scoped storage (MediaStore) + AudioDeviceInfo routing
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // LAME MP3 encoder wrapper (JNI)
    implementation("com.github.naman14:TAndroidLame:1.1")
}
