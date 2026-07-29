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

val apiSource = file("src/main/java/sk/freemap/tracker/TrackerApi.kt")
val apiDoc = rootProject.file("API.md")

/**
 * API.md is the published contract for the local HTTP API, and prose rots silently — an endpoint
 * gets added and the document goes on describing the API as it was six commits ago. This compares
 * the names in the two and fails on any disagreement.
 *
 * It checks names, not meaning: it can tell that `DELETE /track` exists and is written down, not
 * that what is written down about it is true. The prose still has to be kept honest by hand.
 */
tasks.register("checkApiDocs") {
    description = "Fails when API.md and TrackerApi.kt disagree about the HTTP surface."
    group = "verification"
    inputs.file(apiSource)
    inputs.file(apiDoc)
    outputs.file(layout.buildDirectory.file("tmp/checkApiDocs.stamp"))

    doLast {
        val source = apiSource.readText()
        val doc = apiDoc.readText()
        val problems = mutableListOf<String>()

        // Endpoint headings in the document read `### GET /track`.
        val headings = Regex("""(?m)^### ([A-Z]+) (/\w+)$""").findAll(doc)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        // Paths, from the `when (session.uri)` route table.
        val routed = source.substringAfter("private fun route(").substringBefore("// --- endpoints")
        val codePaths = Regex("""\"(/\w+)\"""").findAll(routed).map { it.groupValues[1] }.toSortedSet()
        val docPaths = headings.map { it.second }.toSortedSet()
        (codePaths - docPaths).forEach { problems += "$it is routed but not documented in API.md" }
        (docPaths - codePaths).forEach { problems += "$it is documented in API.md but not routed" }

        // Methods, from the preflight header — the list a browser is told it may use.
        val allowed = Regex("""Access-Control-Allow-Methods", "([^"]+)"""").find(source)
        if (allowed == null) {
            problems += "no Access-Control-Allow-Methods header found in TrackerApi.kt"
        } else {
            val corsMethods = allowed.groupValues[1].split(",").map { it.trim() }.toSortedSet()
            // OPTIONS is answered for every path rather than documented per endpoint.
            val docMethods = (headings.map { it.first } + "OPTIONS").toSortedSet()
            (corsMethods - docMethods).forEach { problems += "$it is allowed by CORS but no endpoint documents it" }
            (docMethods - corsMethods).forEach { problems += "$it is documented but missing from Access-Control-Allow-Methods" }
        }

        // Every JSON key statusJson emits has to have a row of its own in the field table under
        // `### GET /status` — a passing mention in prose or in the example does not count. Nested
        // keys are documented as `version.code`, hence splitting on the dot.
        val statusFn = source.substringAfter("private fun statusJson(").substringBefore("private fun quoted(")
        val codeKeys = Regex("""\\"(\w+)\\":""").findAll(statusFn).map { it.groupValues[1] }.toSortedSet()
        val statusSection = doc.substringAfter("### GET /status").substringBefore("\n### ")
        val docFields = Regex("""(?m)^\| `([\w.]+)`""").findAll(statusSection)
            .flatMap { it.groupValues[1].split(".").asSequence() }
            .toSet()
        (codeKeys - docFields).forEach {
            problems += "GET /status returns \"$it\", which has no row in the API.md field table"
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                problems.joinToString("\n  - ", "API.md is out of sync with TrackerApi.kt:\n  - ")
            )
        }
        logger.lifecycle(
            "checkApiDocs: ${codePaths.size} endpoints, ${codeKeys.size} status fields, all documented"
        )
    }
}

tasks.named("check") {
    dependsOn("checkApiDocs")
}

/**
 * Copies the release APK out under the name the update manifest's `apkUrl` points at — the version
 * has to be in the filename, since the server keeps several of them side by side.
 *
 * The doc check is a dependency rather than a separate step anyone has to remember: this is the task
 * that produces a publishable artefact, so it is the last honest moment to notice drift.
 */
tasks.register<Copy>("releaseApk") {
    description = "Builds the release APK and copies it out as freemap-recorder-<version>.apk."
    group = "build"
    dependsOn("assembleRelease", "checkApiDocs")
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
