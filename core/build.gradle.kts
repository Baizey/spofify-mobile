plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
// The selected source-pinned Go fixture gate fails closed if its private descriptor/pin is absent.
// Portable units retain all synthetic transport/security coverage without starting a Go server.
val offlineApiIntegration = providers.gradleProperty("spofify.offlineApiIntegration")
    .map { it.toBooleanStrict() }.orElse(false)
tasks.withType<Test>().configureEach {
    if (!offlineApiIntegration.get()) exclude("**/OfflineCollectionsGoIntegrationTest.class")
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
