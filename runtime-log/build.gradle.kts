import com.android.build.api.dsl.LibraryExtension

apply(plugin = "com.android.library")

// Подробный журнал работы на диске: слои, компактная строка, фоновая запись, потолок объёма.
// Только JDK: ни Android API, ни корутин, поэтому весь модуль проверяется JVM-тестами.
extensions.configure<LibraryExtension> {
    namespace = "io.github.zapretkvn.runtimelog"
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
