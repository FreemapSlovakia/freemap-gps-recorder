plugins {
    id("com.android.application")
}

android {
    namespace = "sk.freemap.tracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "sk.freemap.tracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
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
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
