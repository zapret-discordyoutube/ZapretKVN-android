import com.android.build.api.dsl.LibraryExtension

apply(plugin = "com.android.library")

// Pure presentation helpers (formatting, Russian plurals, server-name parsing).
// The code must stay free of Android and domain types so host tests stay fast.
extensions.configure<LibraryExtension> {
    namespace = "io.github.zapretkvn.core.uimodel"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // formatLatency reads the latency value types (LatencyProbeState) from :core:model.
    add("api", project(":core:model"))
    add("testImplementation", "junit:junit:4.13.2")
}
