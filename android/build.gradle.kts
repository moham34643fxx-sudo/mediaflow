// Runtime dependencies include Kotlin 2.4 metadata. AGP 8.13.2's bundled
// R8 8.13 cannot parse it; Android's compatibility table requires >=9.1.29.
// Override only the shrinker, using the official narrowly scoped repository.
buildscript {
    repositories {
        maven("https://storage.googleapis.com/r8-releases/raw") {
            content {includeModule("com.android.tools","r8")}
        }
    }
    dependencies {classpath("com.android.tools:r8:9.1.29")}
}
plugins {
    id("com.android.application") version "8.13.2" apply false
    // KT-83372: earlier coroutine data-flow analysis can hang during compilation.
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.3.21" apply false
    id("com.chaquo.python") version "17.1.0" apply false
    id("com.google.dagger.hilt.android") version "2.57.2" apply false
}
tasks.register<Copy>("deliverArtifacts") {
    dependsOn(":app:assembleDebug",":app:assembleRelease")
    from("app/build/outputs/apk/debug/app-universal-debug.apk") { rename { "Z-Downloader-debug.apk" } }
    from("app/build/outputs/apk/release/app-universal-release.apk") { rename { "Z-Downloader-release.apk" } }
    from("app/build/outputs/apk/release/app-arm64-v8a-release.apk") { rename { "Z-Downloader-arm64.apk" } }
    from("app/build/outputs/apk/release/app-x86_64-release.apk") { rename { "Z-Downloader-x86_64.apk" } }
    into("deliverables")
}
// Use -PbundleOnly=true: AGP resource shrinking cannot combine APK splits and AAB.
tasks.register<Copy>("deliverBundle") {
    dependsOn(":app:bundleRelease")
    from("app/build/outputs/bundle/release/app-release.aab") { rename { "Z-Downloader-release.aab" } }
    into("deliverables")
}
