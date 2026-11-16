import com.android.build.api.dsl.LibraryExtension

apply(plugin = "com.android.library")

// Masking ("Маскировка"): the settings model, the global + per-server merge, validation that
// cites the native cores, and the pure emitters that turn settings into the exact JSON fields
// each native core already reads (Xray mux/xmux/finalmask, Amnezia junk and I1-I5, sing-box TLS
// fragment). No I/O and no Android API, so the whole module is checked by JVM tests.
extensions.configure<LibraryExtension> {
    namespace = "io.github.zapretkvn.masking"
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
    add("api", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    add("testImplementation", "junit:junit:4.13.2")
}
