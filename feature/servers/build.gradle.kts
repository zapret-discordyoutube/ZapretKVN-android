import com.android.build.api.dsl.LibraryExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(plugin = "com.android.library")

// Screens, presenters and UI mappers of one feature. They read only :core:* modules:
// state and callbacks arrive as plain arguments from the *Route.kt adapters in :app,
// so this module must never depend on :app (scripts/verify-project.sh enforces it).
extensions.configure<LibraryExtension> {
    namespace = "io.github.zapretkvn.feature.servers"
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

    add("implementation", project(":core:model"))
    add("implementation", project(":core:ui-model"))
    add("implementation", project(":core:designsystem"))
    add("implementation", composeBom)
    add("implementation", "androidx.compose.foundation:foundation")
    add("implementation", "androidx.compose.material:material-icons-core")
    add("implementation", "androidx.compose.material3:material3")
    add("implementation", "androidx.compose.ui:ui")
    add("implementation", "androidx.compose.ui:ui-tooling-preview")
    add("implementation", "androidx.core:core-ktx:1.18.0")
    add("implementation", "androidx.activity:activity-compose:1.13.0")
    add("implementation", "androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    add("implementation", "androidx.savedstate:savedstate-compose:1.3.2")
    add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    add("testImplementation", "junit:junit:4.13.2")
    add("testImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
