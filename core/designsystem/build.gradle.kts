import com.android.build.api.dsl.LibraryExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(plugin = "com.android.library")

// Theme, tokens and shared Compose components. No VPN or profile types here:
// screens map domain state to plain values before calling these components.
extensions.configure<LibraryExtension> {
    namespace = "io.github.zapretkvn.core.designsystem"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    add("api", composeBom)
    add("api", "androidx.compose.foundation:foundation")
    add("api", "androidx.compose.material3:material3")
    add("api", "androidx.compose.material:material-icons-core")
    add("api", "androidx.compose.ui:ui")
    add("api", project(":core:ui-model"))
    // Align transitive lifecycle/savedstate with :app so the module resolves only
    // versions already pinned in gradle/verification-metadata.xml.
    add("implementation", "androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    add("implementation", "androidx.savedstate:savedstate-compose:1.3.2")
    add("implementation", "androidx.core:core-ktx:1.18.0")
    add("implementation", "androidx.activity:activity-compose:1.13.0")
    add("implementation", "androidx.compose.ui:ui-tooling-preview")
    add("testImplementation", "junit:junit:4.13.2")
}
