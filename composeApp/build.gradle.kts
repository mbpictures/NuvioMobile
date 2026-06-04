import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.io.File
import java.net.URI
import java.util.Properties

abstract class GenerateRuntimeConfigsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val localPropertiesFile: RegularFileProperty

    @get:Input
    abstract val appVersionName: Property<String>

    @get:Input
    abstract val appVersionCode: Property<Int>

    @TaskAction
    fun generate() {
        val props = Properties()
        localPropertiesFile.asFile.orNull?.takeIf { it.exists() }?.inputStream()?.use { props.load(it) }

        val outDir = outputDir.get().asFile
        outDir.resolve("com/nuvio/app/core/network").apply {
            mkdirs()
            resolve("SupabaseConfig.kt").writeText(
                """
                |package com.nuvio.app.core.network
                |
                |object SupabaseConfig {
                |    const val URL = "${props.getProperty("SUPABASE_URL", "")}" 
                |    const val ANON_KEY = "${props.getProperty("SUPABASE_ANON_KEY", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/tmdb/TmdbConfig.kt").delete()

        outDir.resolve("com/nuvio/app/features/trakt").apply {
            mkdirs()
            resolve("TraktConfig.kt").writeText(
                """
                |package com.nuvio.app.features.trakt
                |
                |object TraktConfig {
                |    const val CLIENT_ID = "${props.getProperty("TRAKT_CLIENT_ID", "")}" 
                |    const val CLIENT_SECRET = "${props.getProperty("TRAKT_CLIENT_SECRET", "")}" 
                |    const val REDIRECT_URI = "${props.getProperty("TRAKT_REDIRECT_URI", "nuvio://auth/trakt")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/player/skip").apply {
            mkdirs()
            resolve("IntroDbConfig.kt").writeText(
                """
                |package com.nuvio.app.features.player.skip
                |
                |object IntroDbConfig {
                |    const val URL = "${props.getProperty("INTRODB_API_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/details").apply {
            mkdirs()
            resolve("ImdbEpisodeRatingsConfig.kt").writeText(
                """
                |package com.nuvio.app.features.details
                |
                |object ImdbEpisodeRatingsConfig {
                |    const val IMDB_RATINGS_API_BASE_URL = "${props.getProperty("IMDB_RATINGS_API_BASE_URL", "")}" 
                |    const val IMDB_TAPFRAME_API_BASE_URL = "${props.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/debrid").apply {
            mkdirs()
            resolve("PremiumizeConfig.kt").writeText(
                """
                |package com.nuvio.app.features.debrid
                |
                |object PremiumizeConfig {
                |    const val CLIENT_ID = "${props.getProperty("PREMIUMIZE_CLIENT_ID", "")}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/build").apply {
            mkdirs()
            resolve("AppVersionConfig.kt").writeText(
                """
                |package com.nuvio.app.core.build
                |
                |object AppVersionConfig {
                |    const val VERSION_NAME = "${appVersionName.get()}"
                |    const val VERSION_CODE = ${appVersionCode.get()}
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/settings").apply {
            mkdirs()
            resolve("CommunityConfig.kt").writeText(
                """
                |package com.nuvio.app.features.settings
                |
                |object CommunityConfig {
                |    const val CONTRIBUTIONS_URL = "${props.getProperty("CONTRIBUTIONS_URL", "")}" 
                |    const val DONATIONS_BASE_URL = "${props.getProperty("DONATIONS_BASE_URL", "")}" 
                |    const val DONATIONS_DONATE_URL = "${props.getProperty("DONATIONS_DONATE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }
    }
}

abstract class RenameReleaseArtifactTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val extension: Property<String>

    @get:OutputDirectory
    abstract val artifactDirectory: DirectoryProperty

    @TaskAction
    fun renameArtifact() {
        val dir = artifactDirectory.get().asFile
        if (!dir.exists()) return
        val ext = extension.get()
        val targetFile = dir.resolve("Nuvio-${versionName.get()}.$ext")
        val sourceFile = dir.listFiles()
            ?.filter { it.extension == ext && it.name.startsWith("Nuvio-") }
            ?.maxByOrNull { it.lastModified() }
            ?: return

        if (sourceFile.absolutePath != targetFile.absolutePath) {
            targetFile.delete()
            sourceFile.copyTo(targetFile, overwrite = true)
            sourceFile.delete()
        }
    }
}

fun readXcconfigValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex).trim() to line.substring(separatorIndex + 1).trim()
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

val supabaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val releaseStoreFile = supabaseProps.getProperty("NUVIO_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = supabaseProps.getProperty("NUVIO_RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = supabaseProps.getProperty("NUVIO_RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = supabaseProps.getProperty("NUVIO_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeystore = releaseStoreFile?.let(rootProject::file)
val appVersionConfigFile = rootProject.file("iosApp/Configuration/Version.xcconfig")
val releaseAppVersionName = readXcconfigValue(appVersionConfigFile, "MARKETING_VERSION")
    ?: error("MARKETING_VERSION is missing from ${appVersionConfigFile.path}")
val releaseAppVersionCode = readXcconfigValue(appVersionConfigFile, "CURRENT_PROJECT_VERSION")
    ?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION is missing or invalid in ${appVersionConfigFile.path}")
val iosDistribution = (
    providers.gradleProperty("nuvio.ios.distribution").orNull
        ?: System.getenv("NUVIO_IOS_DISTRIBUTION")
        ?: supabaseProps.getProperty("NUVIO_IOS_DISTRIBUTION")
        ?: "appstore"
    ).trim().lowercase()
require(iosDistribution == "appstore" || iosDistribution == "full") {
    "NUVIO_IOS_DISTRIBUTION must be 'appstore' or 'full'."
}
val iosDistributionSourceDir = if (iosDistribution == "full") {
    "src/iosFull/kotlin"
} else {
    "src/iosAppStore/kotlin"
}
val iosFrameworkBundleId = "com.nuvio.media.fork"
val fullCommonSourceDir = project.file("src/fullCommonMain/kotlin")
val generatedRuntimeConfigDir = layout.buildDirectory.dir("generated/runtime-config/kotlin")

val generateRuntimeConfigs = tasks.register<GenerateRuntimeConfigsTask>("generateRuntimeConfigs") {
    outputDir.set(generatedRuntimeConfigDir)
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropertiesFile.set(localPropsFile)
    }
    appVersionName.set(releaseAppVersionName)
    appVersionCode.set(releaseAppVersionCode)
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateRuntimeConfigs)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64()
    )

    iosTargets.forEach { iosTarget ->
        iosTarget.compilations.getByName("main") {
            cinterops {
                create("commoncrypto") {
                    defFile(project.file("src/nativeInterop/cinterop/commoncrypto.def"))
                    compilerOpts("-I${project.projectDir}/src/nativeInterop/cinterop")
                }
            }

            if (iosDistribution == "full") {
                defaultSourceSet.kotlin.srcDir(fullCommonSourceDir)
            }
            defaultSourceSet.kotlin.srcDir(project.file(iosDistributionSourceDir))
            defaultSourceSet.dependencies {
                implementation(libs.ktor.client.darwin)
                if (iosDistribution == "full") {
                    implementation(libs.quickjs.kt)
                    implementation(libs.ksoup)
                }
            }
        }

        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            freeCompilerArgs += listOf("-Xbinary=bundleId=$iosFrameworkBundleId")
        }
    }
    
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedRuntimeConfigDir)
        }
        val desktopMain by getting {
            kotlin.srcDir(fullCommonSourceDir)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.java)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna)
                implementation(libs.quickjs.kt)
                implementation(libs.ksoup)
            }
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.androidx.work.runtime)
            implementation(libs.coil.gif)
            implementation("androidx.recyclerview:recyclerview:1.4.0")
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("com.google.code.gson:gson:2.11.0")
            implementation("io.github.peerless2012:ass-media:0.4.0-beta01")
            implementation(libs.ktor.client.android)
            implementation(libs.androidx.media3.exoplayer.hls)
            implementation(libs.androidx.media3.exoplayer.dash)
            implementation(libs.androidx.media3.exoplayer.smoothstreaming)
            implementation(libs.androidx.media3.exoplayer.rtsp)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.datasource.okhttp)
            implementation(libs.androidx.media3.decoder)
            implementation(libs.androidx.media3.session)
            implementation(libs.androidx.media3.common)
            implementation(libs.androidx.media3.container)
            implementation(libs.androidx.media3.extractor)
            implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("lib-*.aar"))))
        }
        commonMain.dependencies {
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.coil.svg)
            implementation("dev.chrisbanes.haze:haze:1.7.2")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.kermit)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.functions)
            implementation(libs.reorderable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

afterEvaluate {
    dependencies {
        add("fullImplementation", files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
        add("fullImplementation", libs.ksoup)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.nuvio.app.DesktopAppKt"
        buildTypes.release.proguard {
            configurationFiles.from(project.file("desktop-proguard-rules.pro"))
        }
        nativeDistributions {
            packageName = "Nuvio"
            modules("java.net.http")
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            macOS {
                dockName = "Nuvio"
                iconFile.set(project.file("desktop-icons/nuvio.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSRequiresAquaSystemAppearance</key>
                        <false/>
                    """.trimIndent()
                }
            }
            windows {
                // Stable identity for in-place upgrades; never change once published.
                upgradeUuid = "7B3F8C2E-4A1D-4E5F-9D6B-1C2A3B4C5D6E"
                menuGroup = "Nuvio"
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                val winIcon = project.file("desktop-icons/nuvio.ico")
                if (winIcon.exists()) {
                    iconFile.set(winIcon)
                }
            }
            linux {
                // Adds a desktop launcher entry so the installed .deb shows up in app menus.
                menuGroup = "Nuvio"
                shortcut = true
                val linuxIcon = project.file("desktop-icons/nuvio.png")
                if (linuxIcon.exists()) {
                    iconFile.set(linuxIcon)
                }
            }
        }
    }
}

val renameReleaseDmgArtifact = tasks.register<RenameReleaseArtifactTask>("renameReleaseDmgArtifact") {
    versionName.set(releaseAppVersionName)
    extension.set("dmg")
    artifactDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/dmg"))
}

val renameReleaseMsiArtifact = tasks.register<RenameReleaseArtifactTask>("renameReleaseMsiArtifact") {
    versionName.set(releaseAppVersionName)
    extension.set("msi")
    artifactDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/msi"))
}

val renameReleaseExeArtifact = tasks.register<RenameReleaseArtifactTask>("renameReleaseExeArtifact") {
    versionName.set(releaseAppVersionName)
    extension.set("exe")
    artifactDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/exe"))
}

tasks.matching { it.name == "packageReleaseDmg" }.configureEach { finalizedBy(renameReleaseDmgArtifact) }
tasks.matching { it.name == "packageReleaseMsi" }.configureEach { finalizedBy(renameReleaseMsiArtifact) }
tasks.matching { it.name == "packageReleaseExe" }.configureEach { finalizedBy(renameReleaseExeArtifact) }
tasks.matching { it.name == "packageReleaseDistributionForCurrentOS" }.configureEach {
    finalizedBy(renameReleaseDmgArtifact, renameReleaseMsiArtifact, renameReleaseExeArtifact)
}

// The legacy Swift bridge (MPVKit/Sources/DesktopMPVBridge) is no longer used. macOS playback
// now loads libmpv.dylib directly via JNA, mirroring the Windows backend. See fetchMacOSLibmpv
// below for how the dylibs are fetched and patched.

abstract class FetchWindowsLibmpvTask : DefaultTask() {
    @get:Input
    abstract val archiveUrl: Property<String>

    @get:Input
    abstract val extractorUrl: Property<String>

    @get:OutputFile
    abstract val archiveFile: RegularFileProperty

    @get:OutputFile
    abstract val extractorFile: RegularFileProperty

    @get:OutputFile
    abstract val dllFile: RegularFileProperty

    @TaskAction
    fun fetch() {
        downloadIfMissing(archiveFile.get().asFile, archiveUrl.get(), minSize = 1_000_000L, label = "libmpv archive")
        downloadIfMissing(extractorFile.get().asFile, extractorUrl.get(), minSize = 100_000L, label = "7zr extractor")

        val dll = dllFile.get().asFile
        dll.parentFile.mkdirs()
        if (dll.exists()) dll.delete()

        val outDir = dll.parentFile
        val process = ProcessBuilder(
            extractorFile.get().asFile.absolutePath,
            "e",
            archiveFile.get().asFile.absolutePath,
            "-o${outDir.absolutePath}",
            "-y",
            "-r",
            "libmpv-2.dll",
        ).redirectErrorStream(true).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            throw GradleException("7zr extraction failed (exit $exit):\n$stdout")
        }
        if (!dll.exists()) {
            throw GradleException("libmpv-2.dll not found after extraction.\n7zr output:\n$stdout")
        }
        logger.lifecycle("Extracted libmpv-2.dll (${dll.length() / 1024} KiB)")
    }

    private fun downloadIfMissing(target: File, url: String, minSize: Long, label: String) {
        if (target.exists() && target.length() >= minSize) return
        target.parentFile.mkdirs()
        logger.lifecycle("Downloading $label: $url")
        URI(url).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }
}

val libmpvArchiveUrl =
    "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20260527/mpv-dev-x86_64-20260527-git-427e4bf.7z"
val sevenZrExtractorUrl = "https://www.7-zip.org/a/7zr.exe"
val libmpvResourceRoot = layout.buildDirectory.dir("generated/libmpv")

val fetchWindowsLibmpv = tasks.register<FetchWindowsLibmpvTask>("fetchWindowsLibmpv") {
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isWindows }
    archiveUrl.set(libmpvArchiveUrl)
    extractorUrl.set(sevenZrExtractorUrl)
    archiveFile.set(layout.buildDirectory.file("libmpv-cache/libmpv.7z"))
    extractorFile.set(layout.buildDirectory.file("libmpv-cache/7zr.exe"))
    dllFile.set(libmpvResourceRoot.map { it.file("win32-x86-64/libmpv-2.dll") })
}

abstract class FetchMacOSLibmpvTask : DefaultTask() {
    @get:Input
    abstract val archiveUrl: Property<String>

    @get:OutputFile
    abstract val archiveFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val archive = archiveFile.get().asFile
        if (!archive.exists() || archive.length() < 1_000_000L) {
            archive.parentFile.mkdirs()
            logger.lifecycle("Downloading libmpv archive: ${archiveUrl.get()}")
            URI(archiveUrl.get()).toURL().openStream().use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            }
        }

        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val tarProcess = ProcessBuilder(
            "tar", "-xzf", archive.absolutePath, "-C", outDir.absolutePath, "--strip-components=1",
        ).redirectErrorStream(true).start()
        val tarStdout = tarProcess.inputStream.bufferedReader().readText()
        if (tarProcess.waitFor() != 0) {
            throw GradleException("Failed to extract libmpv archive:\n$tarStdout")
        }

        val dylibs = outDir.listFiles { f -> f.isFile && f.name.endsWith(".dylib") }
            ?: throw GradleException("No dylibs found in ${outDir.absolutePath}")
        if (dylibs.none { it.name == "libmpv.dylib" }) {
            throw GradleException("libmpv.dylib missing from extracted archive at ${outDir.absolutePath}")
        }

        // The upstream dylibs reference siblings via @rpath/<name>, but the LC_RPATH entries
        // point at Nix-store paths from the build host. Add @loader_path so dyld finds the
        // sibling dylibs at runtime when they sit next to libmpv.dylib.
        dylibs.forEach { dylib ->
            val patch = ProcessBuilder("install_name_tool", "-add_rpath", "@loader_path", dylib.absolutePath)
                .redirectErrorStream(true).start()
            val out = patch.inputStream.bufferedReader().readText()
            if (patch.waitFor() != 0 && "would duplicate path" !in out) {
                throw GradleException("install_name_tool failed for ${dylib.name}:\n$out")
            }
        }
        logger.lifecycle("Extracted ${dylibs.size} dylibs into ${outDir.absolutePath}")
    }
}

abstract class DownloadFileTask : DefaultTask() {
    @get:Input
    abstract val sourceUrl: Property<String>

    @get:Input
    abstract val minSize: Property<Long>

    @get:OutputFile
    abstract val targetFile: RegularFileProperty

    @TaskAction
    fun download() {
        val target = targetFile.get().asFile
        if (target.exists() && target.length() >= minSize.get()) return
        target.parentFile.mkdirs()
        logger.lifecycle("Downloading ${sourceUrl.get()}")
        URI(sourceUrl.get()).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.length() < minSize.get()) {
            throw GradleException("Downloaded ${target.name} is smaller than expected (${target.length()} bytes)")
        }
    }
}

abstract class FetchLinuxLibmpvTask : DefaultTask() {
    @get:Input
    abstract val embedEnabled: Property<Boolean>

    @get:InputFile
    abstract val scriptFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val out = outputDir.get().asFile
        val script = scriptFile.get().asFile
        val process = ProcessBuilder("bash", script.absolutePath, out.absolutePath)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().forEachLine { line ->
            if (line.isNotBlank()) logger.lifecycle(line)
        }
        val exit = process.waitFor()
        if (exit != 0) {
            throw GradleException("fetch-linux-libmpv.sh failed (exit $exit)")
        }
        if (!out.resolve("libmpv.so.2").isFile) {
            throw GradleException("libmpv.so.2 missing from $out after fetch")
        }
    }
}

val macOSLibmpvArchiveUrl =
    "https://github.com/media-kit/libmpv-darwin-build/releases/download/v0.7.0/libmpv-libs_v0.7.0_macos-universal-video-default.tar.gz"

val fetchMacOSLibmpv = tasks.register<FetchMacOSLibmpvTask>("fetchMacOSLibmpv") {
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isMacOsX }
    archiveUrl.set(macOSLibmpvArchiveUrl)
    archiveFile.set(layout.buildDirectory.file("libmpv-cache/macos-libmpv.tar.gz"))
    outputDir.set(libmpvResourceRoot.map { it.dir("macos-universal") })
}

// Embed libmpv on Linux (self-contained bundle, like Windows/macOS) with
// -Pnuvio.desktop.embedMpvLinux=true. Off by default: dev builds stay fast and the runtime uses a
// system-installed libmpv (sudo apt install libmpv1). The embedded bundle is built from conda-forge
// and needs a modern libstdc++ (Ubuntu 22.04+), so it is opt-in rather than forced.
val embedMpvLinux = (providers.gradleProperty("nuvio.desktop.embedMpvLinux").orNull ?: "false").toBoolean()
val fetchLinuxLibmpv = tasks.register<FetchLinuxLibmpvTask>("fetchLinuxLibmpv") {
    embedEnabled.set(embedMpvLinux)
    // Read the gate from the task's own input (not the script-level `embedMpvLinux` local): an onlyIf
    // spec is stored in the configuration cache, and referencing a top-level script val from it would
    // capture the Build_gradle script object, which is not serializable.
    onlyIf {
        org.gradle.internal.os.OperatingSystem.current().isLinux &&
            (it as FetchLinuxLibmpvTask).embedEnabled.get()
    }
    scriptFile.set(project.file("desktop-scripts/fetch-linux-libmpv.sh"))
    outputDir.set(libmpvResourceRoot.map { it.dir("linux-x86-64") })
}

// Bundle a color-emoji font on Linux: the app's JetBrains Sans font has no emoji glyphs and minimal
// Linux installs ship none, so Skia's fallback finds nothing. macOS/Windows use their OS emoji font.
val emojiFontResourceRoot = layout.buildDirectory.dir("generated/desktop-fonts")
val fetchDesktopEmojiFont = tasks.register<DownloadFileTask>("fetchDesktopEmojiFont") {
    onlyIf { org.gradle.internal.os.OperatingSystem.current().isLinux }
    sourceUrl.set("https://github.com/googlefonts/noto-emoji/raw/v2.047/fonts/NotoColorEmoji.ttf")
    minSize.set(1_000_000L)
    targetFile.set(emojiFontResourceRoot.map { it.file("fonts/NotoColorEmoji.ttf") })
}

kotlin {
    sourceSets {
        val desktopMain by getting {
            resources.srcDir(fetchWindowsLibmpv.map { libmpvResourceRoot.get().asFile })
            resources.srcDir(fetchMacOSLibmpv.map { libmpvResourceRoot.get().asFile })
            resources.srcDir(fetchLinuxLibmpv.map { libmpvResourceRoot.get().asFile })
            resources.srcDir(fetchDesktopEmojiFont.map { emojiFontResourceRoot.get().asFile })
        }
    }
}

configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-ui")
}

android {
    namespace = "com.nuvio.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            if (releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.nuvio.media.fork"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseAppVersionCode
        versionName = releaseAppVersionName
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("playstore") {
            dimension = "distribution"
        }
    }
    sourceSets.getByName("full") {
        manifest.srcFile("src/androidFull/AndroidManifest.xml")
        java.srcDir(fullCommonSourceDir)
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }
    splits {
        abi {
            val splitEnabled = providers.gradleProperty("nuvio.splitAbi").orNull?.toBoolean() == true
            isEnable = splitEnabled
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
