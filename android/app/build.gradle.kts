plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.chaquo.python")
    id("com.google.dagger.hilt.android")
}
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

val productionSigningValues=listOf("ZD_KEYSTORE","ZD_STORE_PASSWORD","ZD_KEY_ALIAS","ZD_KEY_PASSWORD")
    .associateWith {providers.environmentVariable(it).orNull}
val productionSigningRequested=productionSigningValues.values.any {!it.isNullOrBlank()}
val productionSigningComplete=productionSigningValues.values.all {!it.isNullOrBlank()}
val qaSigningOptIn=providers.gradleProperty("mediaflow.qaSigning").map {it.equals("true",ignoreCase=true)}.orElse(false)
val signingMigrationBridge=providers.gradleProperty("mediaflow.signingMigrationBridge").map {it.equals("true",ignoreCase=true)}.orElse(false)
val releaseIdentity=Properties().apply {rootProject.file("config/release-signing.properties").inputStream().use {load(it)}}
android {
    namespace = "com.zdownloader.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.zdownloader.app"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("mediaflow.versionCode").orElse("102").get().toInt()
        versionName = providers.gradleProperty("mediaflow.versionName").orElse("0.9.61").get()
        buildConfigField("boolean","QA_SIGNED_RELEASE",(!productionSigningComplete&&qaSigningOptIn.get()&&!signingMigrationBridge.get()).toString())
        buildConfigField("boolean","INTERNAL_DIAGNOSTICS","false")
        buildConfigField("String","PRODUCTION_SIGNER_SHA256","\"${releaseIdentity.getProperty("productionSha256")}\"")
        buildConfigField("String","LEGACY_SIGNER_SHA256","\"${releaseIdentity.getProperty("legacySha256")}\"")
        buildConfigField("String","UPDATE_FEED_URL","\"https://api.github.com/repos/moham34643fxx-sudo/mediaflow/releases?per_page=20\"")
        buildConfigField("String","UPDATE_REPOSITORY","\"moham34643fxx-sudo/mediaflow\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        providers.gradleProperty("mediaflow.testApplicationId").orNull?.let { testApplicationId = it }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        javaCompileOptions { annotationProcessorOptions { arguments["room.schemaLocation"] = "$projectDir/schemas" } }
    }
    buildFeatures { compose = true; buildConfig = true }
    bundle { language { enableSplit = false } }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/legalAssets"))
    sourceSets["androidTest"].assets.srcDir("schemas")
    splits { abi { isEnable=!providers.gradleProperty("bundleOnly").isPresent; reset(); include("arm64-v8a","x86_64"); isUniversalApk=true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        if(productionSigningComplete) create("production") {
            storeFile = file(productionSigningValues.getValue("ZD_KEYSTORE")!!)
            storePassword = productionSigningValues.getValue("ZD_STORE_PASSWORD")
            keyAlias = productionSigningValues.getValue("ZD_KEY_ALIAS")
            keyPassword = productionSigningValues.getValue("ZD_KEY_PASSWORD")
        }
    }
    testBuildType = providers.gradleProperty("testBuildType").orElse("debug").get()
    buildTypes {
        debug { buildConfigField("boolean","INTERNAL_DIAGNOSTICS","true") }
        release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"); testProguardFiles("test-proguard-rules.pro"); signingConfig=signingConfigs.findByName("production") ?: if(qaSigningOptIn.get()) signingConfigs.getByName("debug") else null }
        create("audit") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".audit"
            versionNameSuffix = "-audit"
            matchingFallbacks += listOf("debug")
        }
        create("candidate0965") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".candidate0965"
            resValue("string", "app_name", "MediaFlow 0.9.66 Test")
            resValue("string", "launcher_name", "MediaFlow 0.9.66 Test")
            matchingFallbacks += listOf("debug")
        }
    }
    if(testBuildType=="release") buildTypes.getByName("release").proguardFiles("instrumentation-keep-rules.pro")
    packaging { resources.excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
val verifyReleaseSigningPolicy by tasks.registering {
    group="verification"
    description="Require production credentials or an explicit local QA signing request."
    doLast {
        check(!signingMigrationBridge.get()||productionSigningComplete) {"A migration bridge requires the explicitly configured preserved legacy key, not an implicit debug fallback."}
        if(productionSigningRequested) {
            check(productionSigningComplete) {"Production signing configuration is incomplete. Provide all four ZD_* signing variables; partial configuration cannot fall back to QA signing."}
            check(file(productionSigningValues.getValue("ZD_KEYSTORE")!!).isFile) {"The configured production keystore is unavailable."}
            val store=KeyStore.getInstance(file(productionSigningValues.getValue("ZD_KEYSTORE")!!),
                productionSigningValues.getValue("ZD_STORE_PASSWORD")!!.toCharArray())
            val certificate=store.getCertificate(productionSigningValues.getValue("ZD_KEY_ALIAS")) as? X509Certificate
                ?: error("The configured production signing certificate is unavailable.")
            certificate.checkValidity()
            val fingerprint=MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") {"%02x".format(it)}
            if(signingMigrationBridge.get()) {
                check(fingerprint==releaseIdentity.getProperty("legacySha256")) {"A migration bridge must use the existing installed-user signing identity."}
                check(android.defaultConfig.versionCode==63) {"The controlled migration bridge must have versionCode 63."}
            } else check(fingerprint==releaseIdentity.getProperty("productionSha256") &&
                    !Regex("(?:^|,)\\s*CN\\s*=\\s*Android Debug\\s*(?:,|$)",RegexOption.IGNORE_CASE).containsMatchIn(certificate.subjectX500Principal.name)) {
                "Production requires the pinned non-debug release certificate. A QA/debug certificate cannot be promoted to production."
            }
            check(android.defaultConfig.versionName.orEmpty().matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
                "Production versionName must contain only the official numeric version, without QA/debug/developer/ABI suffixes."
            }
        } else check(qaSigningOptIn.get()) {"Production signing credentials are required for Release. For a local non-production QA artifact explicitly pass -Pmediaflow.qaSigning=true."}
    }
}
tasks.matching {it.name=="preReleaseBuild"}.configureEach {dependsOn(verifyReleaseSigningPolicy)}
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
chaquopy {
    defaultConfig {
        version = "3.14"
        pip { install("yt-dlp==2026.10.26"); install("yt-dlp-ejs==0.8.0") }
    }
}
kapt { correctErrorTypes = true }
val packageLegalNotices by tasks.registering(Copy::class) {
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from("src/main/cpp/vendor/lame-3.100/COPYING") { rename { "LAME-COPYING.txt" } }
    into(layout.buildDirectory.dir("generated/legalAssets/legal"))
}
tasks.named("preBuild") { dependsOn(packageLegalNotices) }
tasks.register("writeRuntimeInventory") {
    doLast {
        val rows=configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts.sortedBy {it.moduleVersion.id.toString()}.map {artifact->
            "${artifact.moduleVersion.id}|${artifact.file.name}"
        }
        rootProject.file("deliverables/runtime-components.txt").writeText(rows.joinToString("\n")+"\n")
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")
    implementation("com.google.dagger:hilt-android:2.57.2")
    kapt("com.google.dagger:hilt-compiler:2.57.2")
    kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.3.10")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime:2.12.0")
    constraints {
        implementation("com.google.errorprone:error_prone_annotations:2.36.0") {
            because("Keep Firebase runtime and Android test annotation versions consistent")
        }
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.github.jamaismagic.ffmpeg:ffmpeg-kit-main-min-16kb:6.1.4")
    implementation("io.github.dokar3:quickjs-kt:1.0.15")
    implementation("androidx.javascriptengine:javascriptengine:1.1.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    // Android's JSON API needs a real implementation for host-only boundary tests.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.media3:media3-muxer:$media3Version")
    androidTestImplementation("androidx.media3:media3-transformer:$media3Version")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.36.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
