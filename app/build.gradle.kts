plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
android {
    namespace = "dev.spofify.mobile"
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.spofify.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.navigation.compose)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
tasks.withType<Test>().configureEach {
    maxHeapSize = "256m"
    maxParallelForks = 1
    System.getenv("TMPDIR")?.let { systemProperty("java.io.tmpdir", it) }
    System.getenv("HOME")?.let { systemProperty("user.home", it) }
}
