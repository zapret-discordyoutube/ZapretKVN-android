import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val coreProperties = Properties().apply {
    rootProject.file("core.properties").inputStream().use { load(it) }
}
val coreTag = coreProperties.getProperty("CORE_TAG")
val coreCommit = coreProperties.getProperty("CORE_COMMIT")
val corePatchFile = coreProperties.getProperty("CORE_PATCH_FILE")
val corePatchSha256 = coreProperties.getProperty("CORE_PATCH_SHA256")
val libboxAar = layout.projectDirectory.file("libs/libbox.aar").asFile
val libboxMetadata = layout.projectDirectory.file("libs/libbox.properties").asFile
val appVersionCode = providers.gradleProperty("zapretVersionCode")
    .orElse(providers.environmentVariable("ZAPRET_VERSION_CODE"))
    .orElse("200099")
    .get()
    .toInt()
val appVersionName = providers.gradleProperty("zapretVersionName")
    .orElse(providers.environmentVariable("ZAPRET_VERSION_NAME"))
    .orElse("0.2.0")
    .get()
val defaultUpdateChannel = if (
    Regex("""^\d+\.\d+\.\d+-beta\.\d+$""").matches(appVersionName)
) {
    "Beta"
} else {
    "Stable"
}
val debugVersionNameSuffix = providers.gradleProperty("zapretDebugVersionNameSuffix")
    .orElse("-debug")
    .get()
val updateRepository = providers.gradleProperty("zapretUpdateRepository")
    .orElse(providers.environmentVariable("ZAPRET_UPDATE_REPOSITORY"))
    .orElse("zapretkvn/ZapretKVN-android")
    .get()
    .also { require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(it)) }
val supportedApkAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
val requestedApkAbi = providers.gradleProperty("zapretAbi")
    .orElse(providers.gradleProperty("zapretReleaseAbi"))
    .orNull
    ?.also { require(it in supportedApkAbis) { "Unsupported APK ABI: $it" } }
val signingStorePath = providers.environmentVariable("ZAPRET_SIGNING_STORE_FILE").orNull
val signingStorePassword = providers.environmentVariable("ZAPRET_SIGNING_STORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("ZAPRET_SIGNING_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("ZAPRET_SIGNING_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    signingStorePath,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword,
).all { !it.isNullOrBlank() }
val releaseSigningValueCount = listOf(
    signingStorePath,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword,
).count { !it.isNullOrBlank() }
require(releaseSigningValueCount == 0 || releaseSigningValueCount == 4) {
    "Release signing requires all four ZAPRET_SIGNING_* environment variables."
}

android {
    namespace = "io.github.zapretkvn.android"
    compileSdk = 36
    ndkVersion = coreProperties.getProperty("ANDROID_NDK_VERSION")

    defaultConfig {
        applicationId = "io.github.zapretkvn.android"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        requestedApkAbi?.let { abi ->
            ndk {
                abiFilters += abi
            }
        }

        buildConfigField("String", "CORE_TAG", "\"$coreTag\"")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
        buildConfigField("String", "DEFAULT_UPDATE_CHANNEL", "\"$defaultUpdateChannel\"")
        buildConfigField(
            "String",
            "CORE_COMMIT",
            "\"$coreCommit\"",
        )
        buildConfigField("String", "CORE_PATCH_SHA256", "\"$corePatchSha256\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(signingStorePath))
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            if (debugVersionNameSuffix.isNotEmpty()) {
                versionNameSuffix = debugVersionNameSuffix
            }
            if (requestedApkAbi == null) {
                ndk {
                    // CI and the default local Android test target use an x86_64 AVD.
                    // Device tests can select another single ABI with -PzapretAbi.
                    abiFilters += "x86_64"
                }
            }
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            if (requestedApkAbi == null) {
                ndk {
                    // A direct assembleRelease remains small and predictable. The release
                    // workflow passes zapretAbi once per supported architecture.
                    abiFilters += "arm64-v8a"
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation(files(libboxAar))
    implementation(project(":app-updater"))
    implementation(project(":network-bootstrap"))
    implementation(project(":wireguard-import"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Compose UI test currently carries an older transitive Espresso release.
    // Pin the API 37-compatible stable test stack explicitly.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

val verifyPinnedLibbox by tasks.registering {
    group = "verification"
    description = "Fails when the pinned libbox AAR has not been built."
    inputs.files(libboxAar, libboxMetadata)
    inputs.property("expectedCoreTag", coreTag)
    inputs.property("expectedCoreCommit", coreCommit)
    inputs.property("expectedCorePatchFile", corePatchFile)
    inputs.property("expectedCorePatchSha256", corePatchSha256)
    doLast {
        val aar = inputs.files.single { it.name == "libbox.aar" }
        val metadataFile = inputs.files.single { it.name == "libbox.properties" }
        check(aar.isFile && aar.length() > 0L) {
            "Missing app/libs/libbox.aar. Run scripts/build-core.sh first."
        }
        check(metadataFile.isFile) {
            "Missing app/libs/libbox.properties. Run scripts/build-core.sh first."
        }

        val metadata = Properties().apply {
            metadataFile.inputStream().use { load(it) }
        }
        check(metadata.getProperty("CORE_TAG") == inputs.properties["expectedCoreTag"]) {
            "libbox tag does not match core.properties. Rebuild the core."
        }
        check(metadata.getProperty("CORE_COMMIT") == inputs.properties["expectedCoreCommit"]) {
            "libbox commit does not match core.properties. Rebuild the core."
        }
        check(metadata.getProperty("CORE_PATCH_FILE") == inputs.properties["expectedCorePatchFile"]) {
            "libbox patch file does not match core.properties. Rebuild the core."
        }
        check(metadata.getProperty("CORE_PATCH_SHA256") == inputs.properties["expectedCorePatchSha256"]) {
            "libbox patch hash does not match core.properties. Rebuild the core."
        }

        val digest = MessageDigest.getInstance("SHA-256")
        aar.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        check(metadata.getProperty("LIBBOX_SHA256") == actualSha256) {
            "libbox SHA-256 does not match its build metadata. Rebuild the core."
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyPinnedLibbox)
}
