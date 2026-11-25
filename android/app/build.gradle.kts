plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.chaquo.python")
    id("com.google.dagger.hilt.android")
}
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.Task
import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import java.util.zip.ZipFile
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.nio.file.Files
import java.nio.file.StandardCopyOption

dependencyLocking {
    lockAllConfigurations()
}

val productionSigningValues=listOf("ZD_KEYSTORE","ZD_STORE_PASSWORD","ZD_KEY_ALIAS","ZD_KEY_PASSWORD")
    .associateWith {providers.environmentVariable(it).orNull}
val productionSigningRequested=productionSigningValues.values.any {!it.isNullOrBlank()}
val productionSigningComplete=productionSigningValues.values.all {!it.isNullOrBlank()}
val qaVersionCode=123
val qaSigningOptIn=providers.gradleProperty("mediaflow.qaSigning").map {it.equals("true",ignoreCase=true)}.orElse(false)
val qaApplicationIdSuffix=".candidate0965"
val qaTestApplicationId="com.zdownloader.app.test"
val removedPackageOverrideProperties=listOf(
    "mediaflow.auditApplicationIdSuffix",
    "mediaflow.qaApplicationIdSuffix",
    "mediaflow.testApplicationId"
).filter {providers.gradleProperty(it).isPresent}
require(removedPackageOverrideProperties.isEmpty()) {
    "Custom QA/test package IDs are disabled; remove these Gradle properties: ${removedPackageOverrideProperties.joinToString()}"
}
val signingMigrationBridge=providers.gradleProperty("mediaflow.signingMigrationBridge").map {it.equals("true",ignoreCase=true)}.orElse(false)
val releaseIdentity=Properties().apply {
    rootProject.file("config/release-signing.properties").inputStream().use {load(it)}
}
val adblockNativeProject=rootProject.file("adblock-native")
val cargoTargetDirectory=providers.gradleProperty("mediaflow.cargoTargetDir")
    .map {rootProject.file(it)}
    .orElse(adblockNativeProject.resolve("target"))
val adblockLists=file("src/main/assets/adblock")
val roomSchemaOutput=providers.gradleProperty("mediaflow.roomSchemaOutput")
    .map {rootProject.file(it).absolutePath}
    .orElse(file("schemas").absolutePath)
val generatedAdblockAssets=layout.buildDirectory.dir("generated/adblockAssets")
val generatedAdblockJniLibs=layout.buildDirectory.dir("generated/adblockJniLibs")
android {
    namespace = "com.zdownloader.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.zdownloader.app"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("mediaflow.versionCode").orElse("107").get().toInt()
        versionName = providers.gradleProperty("mediaflow.versionName").orElse("0.9.61").get()
        buildConfigField("boolean","QA_SIGNED_RELEASE",(!productionSigningComplete&&qaSigningOptIn.get()&&!signingMigrationBridge.get()).toString())
        buildConfigField("boolean","INTERNAL_DIAGNOSTICS","false")
        buildConfigField("String","PRODUCTION_SIGNER_SHA256","\"${releaseIdentity.getProperty("productionSha256","")}\"")
        buildConfigField("String","LEGACY_SIGNER_SHA256","\"${releaseIdentity.getProperty("legacySha256","")}\"")
        buildConfigField("String","UPDATE_FEED_URL","\"https://api.github.com/repos/moham34643fxx-sudo/mediaflow/releases?per_page=20\"")
        buildConfigField("String","UPDATE_REPOSITORY","\"moham34643fxx-sudo/mediaflow\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testApplicationId = qaTestApplicationId
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        javaCompileOptions { annotationProcessorOptions { arguments["room.schemaLocation"] = roomSchemaOutput.get() } }
    }
    buildFeatures { compose = true; buildConfig = true }
    bundle { language { enableSplit = false } }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/legalAssets"))
    sourceSets["main"].assets.srcDir(generatedAdblockAssets)
    sourceSets["main"].jniLibs.srcDir(generatedAdblockJniLibs)
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
        debug {
            applicationIdSuffix = qaApplicationIdSuffix
            versionNameSuffix = "-qa-r19"
            resValue("string","app_name","MediaFlow QA R19")
            resValue("string","launcher_name","MediaFlow QA R19")
            buildConfigField("boolean","INTERNAL_DIAGNOSTICS","true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("test-proguard-rules.pro")
            signingConfig=signingConfigs.findByName("production") ?: if(qaSigningOptIn.get()) signingConfigs.getByName("debug") else null
            if(!productionSigningComplete&&qaSigningOptIn.get()) {
                applicationIdSuffix = qaApplicationIdSuffix
                versionNameSuffix = "-qa-r19"
                resValue("string","app_name","MediaFlow QA R19")
                resValue("string","launcher_name","MediaFlow QA R19")
            }
        }
        create("audit") {
            initWith(getByName("debug"))
            applicationIdSuffix = qaApplicationIdSuffix
            versionNameSuffix = "-audit"
            matchingFallbacks += listOf("debug")
        }
        create("candidate0965") {
            initWith(getByName("debug"))
            applicationIdSuffix = qaApplicationIdSuffix
            matchingFallbacks += listOf("debug")
        }
    }
    if(testBuildType=="release") {
        require(qaSigningOptIn.get()&&!productionSigningRequested&&!signingMigrationBridge.get()) {
            "Release instrumentation keep rules are allowed only in an explicitly QA-signed build."
        }
        buildTypes.getByName("release").proguardFiles("instrumentation-keep-rules.pro", "instrumentation-app-keep-rules.pro")
    }
    packaging { resources.excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
androidComponents {
    onVariants(selector().all()) {variant->
        val qaRelease=variant.buildType=="release"&&!productionSigningComplete&&qaSigningOptIn.get()
        if(variant.buildType in setOf("debug","audit","candidate0965")||qaRelease) {
            variant.outputs.forEach {output->
                output.versionCode.set(maxOf(output.versionCode.get(),qaVersionCode))
            }
        }
    }
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
val nativeNoticeDirectory = "legal/dependencies"
val nativeNoticeAssets = layout.projectDirectory.dir("src/main/assets/$nativeNoticeDirectory")
val nativeNoticeManifest = rootProject.layout.projectDirectory.file("gradle/native-notices.sha256")

fun readNativeNoticePins(manifest: File): Map<String, String> {
    val entry = Regex("""^([0-9a-f]{64})  (native-[a-zA-Z0-9-]+\.txt)$""")
    val pins = linkedMapOf<String, String>()
    for (line in manifest.readLines()) {
        val (hash, name) = checkNotNull(entry.matchEntire(line)) { "Malformed native notice pin: $line" }.destructured
        check(pins.put(name, hash) == null) { "Duplicate native notice pin: $name" }
    }
    check(pins.keys.containsAll(setOf(
        "native-quickjs-LICENSE.txt", "native-quickjs-kt-LICENSE.txt", "native-quickjs-c-vector-LICENSE.txt",
        "native-quickjs-headers-NOTICE.txt", "native-quickjs-unicode17-LICENSE.txt",
        "native-quickjs-unicode17-NOTICE.txt", "native-ffmpeg-GPLv3.txt", "native-ffmpeg-LGPLv3.txt",
        "native-ffmpeg-NOTICE.txt", "native-ffmpeg-cpu-features-LICENSE.txt"
    ))) { "The native notice manifest must retain QuickJS and FFmpeg notices" }
    return pins
}

fun verifyNoticeHash(location: String, bytes: ByteArray, expected: String) {
    val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    check(actual == expected) { "Native notice differs from the pinned text: $location" }
}

val verifyReleaseNoticeAssets by tasks.registering {
    group = "verification"
    inputs.file(nativeNoticeManifest)
    inputs.dir(nativeNoticeAssets)
    doLast {
        for ((name, hash) in readNativeNoticePins(nativeNoticeManifest.asFile)) {
            val source = nativeNoticeAssets.file(name).asFile
            check(source.isFile) { "Missing native notice: $name" }
            verifyNoticeHash(name, source.readBytes(), hash)
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseNoticeAssets)
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
    val requiredNames = if (providers.gradleProperty("bundleOnly").isPresent) {
        setOf("app-release.apk")
    } else {
        setOf("app-arm64-v8a-release.apk", "app-x86_64-release.apk", "app-universal-release.apk")
    }
    val verifyApks = tasks.register("verifyReleaseApkNotices") {
        group = "verification"
        inputs.file(nativeNoticeManifest)
        inputs.dir(apkDirectory)
        doLast {
            val apks = apkDirectory.get().asFile.listFiles { file -> file.isFile && file.extension == "apk" }.orEmpty()
            check(apks.map { it.name }.toSet() == requiredNames) { "Unexpected release APK set for notice verification" }
            val pins = readNativeNoticePins(nativeNoticeManifest.asFile)
            for (apk in apks) ZipFile(apk).use { zip ->
                for ((name, hash) in pins) {
                    val path = "assets/$nativeNoticeDirectory/$name"
                    val entry = checkNotNull(zip.getEntry(path)) { "Missing $path from ${apk.name}" }
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    verifyNoticeHash("${apk.name}/$path", bytes, hash)
                }
            }
        }
    }
    tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(verifyApks) }
}

val compileAdblockBaseline by tasks.registering(Exec::class) {
    val manifest=adblockNativeProject.resolve("Cargo.toml")
    val lockfile=adblockNativeProject.resolve("Cargo.lock")
    val easylist=adblockLists.resolve("easylist.txt")
    val easyprivacy=adblockLists.resolve("easyprivacy.txt")
    val binary=generatedAdblockAssets.map {it.file("adblock/easylist-easyprivacy.dat")}
    val statistics=generatedAdblockAssets.map {it.file("adblock/filter-stats.json")}
    val playerScript=generatedAdblockAssets.map {it.file("adblock/youtube-player-document-start.js")}
    inputs.file(manifest)
    inputs.file(lockfile)
    inputs.dir(adblockNativeProject.resolve("src"))
    inputs.files(easylist,easyprivacy)
    inputs.file(adblockNativeProject.resolve("resources/mediaflow-youtube-player.js"))
    outputs.files(binary,statistics,playerScript)
    environment("CARGO_TARGET_DIR",cargoTargetDirectory.get().absolutePath)
    workingDir(rootProject.projectDir)
    doFirst {generatedAdblockAssets.get().asFile.mkdirs()}
    commandLine(
        "cargo","run","--locked","--release","--manifest-path",manifest.absolutePath,
        "--bin","compile_filter_baseline","--",
        easylist.absolutePath,easyprivacy.absolutePath,
        binary.get().asFile.absolutePath,statistics.get().asFile.absolutePath,
        playerScript.get().asFile.absolutePath
    )
}
val buildAdblockNativeLibraries by tasks.registering {
    val manifest=adblockNativeProject.resolve("Cargo.toml")
    val lockfile=adblockNativeProject.resolve("Cargo.lock")
    inputs.file(manifest)
    inputs.file(lockfile)
    inputs.dir(adblockNativeProject.resolve("src"))
    outputs.files(
        generatedAdblockJniLibs.map {it.file("arm64-v8a/libmediaflow_adblock.so")},
        generatedAdblockJniLibs.map {it.file("x86_64/libmediaflow_adblock.so")}
    )
    doLast {
        val hostTag=when {
            System.getProperty("os.name").startsWith("Windows",ignoreCase=true)->"windows-x86_64"
            System.getProperty("os.name").startsWith("Mac",ignoreCase=true)->"darwin-x86_64"
            else->"linux-x86_64"
        }
        val toolchain=android.ndkDirectory.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
        val variants=listOf(
            Triple("aarch64-linux-android","arm64-v8a","aarch64-linux-android24-clang"),
            Triple("x86_64-linux-android","x86_64","x86_64-linux-android24-clang")
        )
        for((target,abi,linkerName) in variants) {
            val linker=toolchain.resolve(linkerName+if(hostTag.startsWith("windows")) ".cmd" else "")
            check(linker.isFile) {"Android NDK Rust linker is unavailable: $linker"}
            exec {
                workingDir(rootProject.projectDir)
                environment("CARGO_TARGET_DIR",cargoTargetDirectory.get().absolutePath)
                environment("CARGO_TARGET_${target.replace('-','_').uppercase()}_LINKER",linker.absolutePath)
                commandLine(
                    "cargo","build","--locked","--release","--lib","--target",target,
                    "--manifest-path",manifest.absolutePath
                )
            }
            val built=cargoTargetDirectory.get().resolve("$target/release/libmediaflow_adblock.so")
            check(built.isFile) {"Rust did not produce the ad blocker library for $abi."}
            val destination=generatedAdblockJniLibs.get().file("$abi/libmediaflow_adblock.so").asFile
            destination.parentFile.mkdirs()
            Files.copy(
                built.toPath(),destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}
val verifyNoDestructiveTestPackageCommands by tasks.registering {
    group="verification"
    description="Reject literal package uninstall or app-data clearing commands in automated test sources."
    doLast {
        val forbidden=Regex(
            """(?i)(?:\bpm\s+(?:uninstall|clear)\b|\bcmd\s+package\s+(?:uninstall|clear)\b|\badb(?:\.exe)?\s+(?:(?:-s|--serial)\s+\S+\s+|-[^\s]+\s+)*(?:uninstall\b|shell\s+(?:pm\s+(?:uninstall|clear)|cmd\s+package\s+(?:uninstall|clear)))|\b(?:deletePackageAsUser|clearApplicationUserData)\b|\bdeletePackage\s*\(|\b[A-Za-z_$][\w$]*\s*\.\s*uninstall\s*\()"""
        )
        val sourceExtensions=setOf("kt","java","kts","gradle","sh","ps1","bat","cmd","py","xml","properties")
        val roots=listOf(file("src/androidTest"),file("src/test")).filter {it.isDirectory}
        val violations=roots.flatMap {root->
            root.walkTopDown().filter {it.isFile&&it.extension.lowercase() in sourceExtensions}.flatMap {source->
                val contents=source.readText()
                forbidden.findAll(contents).map {match->
                    val line=1+contents.substring(0,match.range.first).count {it=='\n'}
                    "${source.relativeTo(projectDir)}:$line"
                }.toList()
            }
        }
        check(violations.isEmpty()) {
            "Automated tests must not uninstall packages or clear app data:\n${violations.joinToString("\n")}"
        }
    }
}
tasks.matching {it.name=="check"||it.name.endsWith("UnitTest")}.configureEach {
    dependsOn(verifyNoDestructiveTestPackageCommands)
}
val allowIsolatedEmulatorMutation=providers.gradleProperty("mediaflow.allowIsolatedEmulatorMutation")
    .map {it.equals("true",ignoreCase=true)}.orElse(false)
val isolatedEmulatorSerial=providers.gradleProperty("mediaflow.isolatedEmulatorSerial").orNull
val androidSerial=providers.environmentVariable("ANDROID_SERIAL").orNull
val guardedDeviceTask=Regex(
    "^(?:connected[A-Za-z0-9]*AndroidTest|connectedCheck|device[A-Za-z0-9]*AndroidTest|deviceCheck|uninstallAll)$"
)
val guardedDeviceTaskTypes=setOf(
    "InstallVariantTask",
    "UninstallTask",
    "DeviceProviderInstrumentTestTask",
    "ManagedDeviceInstrumentationTestTask"
)
val isDeviceMutationTask: (Task) -> Boolean = { task->
    var currentType: Class<*>?=task.javaClass
    var matchedType=false
    while(currentType!=null) {
        val simpleName=currentType.simpleName
        if(simpleName in guardedDeviceTaskTypes ||
            (simpleName.contains("Device")&&simpleName.contains("InstrumentationTest")&&simpleName.endsWith("Task"))) {
            matchedType=true
            break
        }
        currentType=currentType.superclass
    }
    guardedDeviceTask.matches(task.name)||matchedType
}
val verifyIsolatedEmulatorMutation: (List<String>) -> Unit = { taskPaths ->
    check(allowIsolatedEmulatorMutation.get()) {
        "Blocked device-mutating task graph (${taskPaths.joinToString()}). Use a separately approved, isolated local emulator workflow."
    }
    val serial=isolatedEmulatorSerial
    check(!serial.isNullOrBlank()&&serial.matches(Regex("^emulator-[0-9]+$"))) {
        "Device tasks require -Pmediaflow.isolatedEmulatorSerial=emulator-N and an Android emulator; physical devices are not accepted."
    }
    check(androidSerial==serial) {
        "ANDROID_SERIAL must exactly match mediaflow.isolatedEmulatorSerial so Gradle cannot target another device."
    }
    val sdkRoot=providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: providers.environmentVariable("ANDROID_HOME").orNull
        ?: error("ANDROID_SDK_ROOT or ANDROID_HOME is required to verify the isolated emulator target.")
    check(sdkRoot.isNotBlank()) {"ANDROID_SDK_ROOT or ANDROID_HOME is required to verify the isolated emulator target."}
    val adbName=if(System.getProperty("os.name").startsWith("Windows",ignoreCase=true)) "adb.exe" else "adb"
    val adb=file(sdkRoot).resolve("platform-tools").resolve(adbName)
    check(adb.isFile) {"ADB was not found at the configured Android SDK platform-tools path."}
    val adbProcess=ProcessBuilder(adb.absolutePath,"devices").redirectErrorStream(true).start()
    val adbOutput=adbProcess.inputStream.bufferedReader(Charsets.UTF_8).use {it.readText()}
    check(adbProcess.waitFor()==0) {"ADB could not enumerate connected endpoints."}
    val endpoints=adbOutput.lineSequence()
        .map {line->line.trim()}
        .filter {line->line.isNotEmpty()&&!line.startsWith("List of devices attached")}
        .mapNotNull {line->
            val fields=line.split(Regex("\\s+"))
            if(fields.size>=2) fields[0] to fields[1] else null
        }.toList()
    check(endpoints==listOf(serial to "device")) {
        "ADB must report exactly the opted-in emulator as the only device; found ${endpoints.joinToString()}."
    }
}
gradle.taskGraph.whenReady {
    val deviceTasks=allTasks.filter(isDeviceMutationTask)
    if(deviceTasks.isNotEmpty()) verifyIsolatedEmulatorMutation(deviceTasks.map {it.path})
}
tasks.configureEach {
    if(isDeviceMutationTask(this)) {
        dependsOn(verifyNoDestructiveTestPackageCommands)
    }
}
tasks.named("preBuild") { dependsOn(packageLegalNotices) }
tasks.named("preBuild") { dependsOn(compileAdblockBaseline,buildAdblockNativeLibraries) }
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
    implementation("androidx.webkit:webkit:1.13.0")
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
