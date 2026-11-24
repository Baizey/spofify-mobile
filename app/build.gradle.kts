import com.android.build.api.variant.HostTestBuilder

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
    buildTypes {
        release {
            applicationIdSuffix = ".release"
            isMinifyEnabled = false
            vcsInfo.include = false // The canonical GitHub release tag records the source revision.
            // Keep Gradle release APKs unsigned. The protected workflow signs the one universal APK.
        }
    }
}

// AGP enables unit tests only for its default test build type; CI also tests release.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) {
    it.hostTests.getValue(HostTestBuilder.UNIT_TEST_TYPE).enable = true
}

val releaseCode = providers.gradleProperty("mobileReleaseVersionCode").orNull
val releaseName = providers.gradleProperty("mobileReleaseVersionName").orNull
require((releaseCode == null) == (releaseName == null)) {
    "Supply both mobileReleaseVersionCode and mobileReleaseVersionName, or neither."
}
if (releaseCode != null && releaseName != null) {
    val code = releaseCode.toIntOrNull()
    require(releaseCode.matches(Regex("[1-9][0-9]{0,9}")) && code != null && code <= 2_100_000_000) {
        "mobileReleaseVersionCode must be a positive decimal integer, at most 2100000000."
    }
    require(releaseName.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,79}"))) {
        "mobileReleaseVersionName must be 1–80 ASCII letters/digits or .+_-, starting with a letter or digit."
    }
    androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(code)
            output.versionName.set(releaseName)
        }
    }
}

dependencies {
    implementation(project(":core"))
    releaseImplementation(libs.okhttp)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
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
