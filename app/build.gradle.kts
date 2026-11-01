plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.offlinetalks"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.offlinetalks"
        minSdk = 26
        targetSdk = 34
        versionCode = 31
        versionName = "2.0.1"
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
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
}
