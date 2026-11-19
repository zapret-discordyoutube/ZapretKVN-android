import com.android.build.api.dsl.LibraryExtension

apply(plugin = "com.android.library")

// Отчёты о подключениях для status.zapret.moe (reports.zapret.moe, контракт vpnbot client_reports schema 1):
// формат, очередь на диске, классификация попытки, сетевой токен. Только JDK и kotlinx-json, без Android API,
// поэтому весь модуль проверяется JVM-тестами; сеть и привязка к Android — в приложении.
extensions.configure<LibraryExtension> {
    namespace = "io.github.zapretkvn.clientreports"
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
    add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    add("testImplementation", "junit:junit:4.13.2")
}
