import com.android.build.api.dsl.LibraryExtension

apply(plugin = "com.android.library")

// Domain value types the UI layer reads: VPN/profile/routing/app-scope/diagnostic/update state,
// settings and their trivial pure helpers. Package names are the original
// io.github.zapretkvn.android.* ones, so moving a type here changes no imports.
// No Compose, no Android services, no ViewModels, no stores: runtime logic stays in :app.
extensions.configure<LibraryExtension> {
    namespace = "io.github.zapretkvn.core.model"
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
    add("testImplementation", "junit:junit:4.13.2")
}
