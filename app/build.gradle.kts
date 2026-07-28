import java.util.Properties

plugins {
    id("com.android.application")
}

// Single source of truth for the version: the manifest, BuildConfig, `GET /status` and the
// publishable APK filename are all derived from these two, so they cannot drift apart.
val trackerVersionCode = providers.gradleProperty("tracker.versionCode").get().toInt()
val trackerVersionName = providers.gradleProperty("tracker.versionName").get()
val updateManifestUrl = providers.gradleProperty("tracker.updateManifestUrl").get()

/**
 * Release signing credentials, which are never in the repository. Environment variables win, so a
 * build machine needs no files; otherwise they come from `keystore.properties` beside this project
 * (gitignored) or from `~/.gradle/gradle.properties`, which is outside the repository entirely.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun credential(environment: String, property: String): String? =
    System.getenv(environment)
        ?: keystoreProperties.getProperty(property)
        ?: providers.gradleProperty(property).orNull

val storeFilePath = credential("FREEMAP_TRACKER_STORE_FILE", "tracker.storeFile")
val storePasswordValue = credential("FREEMAP_TRACKER_STORE_PASSWORD", "tracker.storePassword")
val keyAliasName = credential("FREEMAP_TRACKER_KEY_ALIAS", "tracker.keyAlias")
val keyPasswordValue = credential("FREEMAP_TRACKER_KEY_PASSWORD", "tracker.keyPassword")
val canSign = storeFilePath != null && storePasswordValue != null &&
    keyAliasName != null && keyPasswordValue != null

android {
    namespace = "sk.freemap.tracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "sk.freemap.tracker"
        minSdk = 26
        targetSdk = 36
        versionCode = trackerVersionCode
        versionName = trackerVersionName

        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = file(storeFilePath!!)
                storePassword = storePasswordValue
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue

                // v1 is only for API < 24, which minSdk already excludes. v3 is worth having on top
                // of v2: it is the scheme that understands key rotation, and it is the nearest thing
                // to an escape route from a lost or compromised key when there is no Play App
                // Signing to fall back on.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        } else {
            logger.lifecycle(
                "freemap-tracker: no release signing credentials found — " +
                    "assembleRelease will produce an unsigned APK. See README, Signing."
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // No native code and no splits, so the one APK `assembleRelease` produces is already universal.
    // App bundles are a Play distribution format and would be useless for a direct download.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * Copies the release APK out under the name the update manifest's `apkUrl` points at — the version
 * has to be in the filename, since the server keeps several of them side by side.
 */
tasks.register<Copy>("releaseApk") {
    description = "Builds the release APK and copies it out as freemap-recorder-<version>.apk."
    group = "build"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(layout.buildDirectory.dir("distributions"))
    rename { "freemap-recorder-$trackerVersionName.apk" }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
