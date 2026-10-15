import com.android.build.api.dsl.LibraryExtension

apply(plugin = "com.android.library")

extensions.configure<LibraryExtension> {
    namespace = "io.github.zapretkvn.wireguardimport"
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
    add("implementation", project(":core:model"))
    add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    add("testImplementation", "junit:junit:4.13.2")
}
