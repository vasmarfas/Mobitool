import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.compose.ui)
    implementation(libs.compose.components.resources)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

val macAppStore = providers.gradleProperty("macAppStore").map { it.isEmpty() || it.toBoolean() }.getOrElse(false)
val macAppStoreIdentity = providers.gradleProperty("macAppStoreIdentity")
    .orElse(providers.environmentVariable("APPLE_MAS_IDENTITY")).orNull?.takeIf { it.isNotBlank() }
val developerIdIdentity = providers.environmentVariable("APPLE_DEVELOPER_ID_IDENTITY").orNull?.takeIf { it.isNotBlank() }
val macArm = System.getProperty("os.arch") == "aarch64"
val macAppResources = layout.buildDirectory.dir("macAppStore/resources")

val nativeJars = configurations.runtimeClasspath.map { classpath ->
    classpath.incoming.artifactView {
        componentFilter { it is ModuleComponentIdentifier && it.group in setOf("org.bytedeco", "net.java.dev.jna") }
    }.files
}
val unpackMacNatives = tasks.register<Sync>("unpackMacNatives") {
    from(nativeJars.map { jars -> jars.map { zipTree(it) } }) {
        include("org/bytedeco/*/macosx-${if (macArm) "arm64" else "x86_64"}/*")
        include("com/sun/jna/darwin-${if (macArm) "aarch64" else "x86-64"}/*.jnilib")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(macAppResources.map { it.dir("macos") })
}

compose.desktop {
    application {
        mainClass = "com.vasmarfas.card.MainKt"
        if (macAppStore) {
            jvmArgs(
                "-Djava.library.path=\$APPDIR/resources",
                "-Dorg.bytedeco.javacpp.pathsFirst=true",
                "-Djna.boot.library.path=\$APPDIR/resources",
                "-Djna.nounpack=true",
                "-Dmobitool.ffmpeg.dir=\$APPDIR/resources",
            )
        }

        nativeDistributions {
            if (macAppStore) appResourcesRootDir.set(macAppResources)
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Pkg,
                TargetFormat.Msi,
                TargetFormat.Exe,
                TargetFormat.Deb,
                TargetFormat.Rpm,
            )
            packageName = "Mobitool"
            packageVersion = (rootProject.extra["appVersionName"] as String).substringBefore('-')
            vendor = "vasmarfas"
            description = "vasmarfas Mobitool: business card, portfolio and a multitool"
            modules("jdk.unsupported", "java.naming", "java.net.http")

            windows {
                menuGroup = "vasmarfas"
                shortcut = true
                perUserInstall = true
                iconFile.set(project.file("icons/logo.ico"))
                upgradeUuid = "8B2C1A7E-6F3D-4B2A-9C1E-5D7F0A3B9E21"
            }
            macOS {
                bundleID = "com.vasmarfas.mobitool"
                minimumSystemVersion = "12.0"
                iconFile.set(project.file("icons/logo.icns"))
                packageBuildVersion = rootProject.extra["appVersionCode"].toString()
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>The sound level meter, tuner and spectrum analyzer listen to the microphone.</string>
                    """.trimIndent()
                }
                if (macAppStore) {
                    appStore = true
                    appCategory = "public.app-category.utilities"
                    entitlementsFile.set(project.file("macos/entitlements.plist"))
                    runtimeEntitlementsFile.set(project.file("macos/runtime-entitlements.plist"))
                    project.file("macos/embedded.provisionprofile").takeIf { it.exists() }?.let { provisioningProfile.set(it) }
                    project.file("macos/runtime.provisionprofile").takeIf { it.exists() }?.let { runtimeProvisioningProfile.set(it) }
                    if (macAppStoreIdentity != null) {
                        signing {
                            sign.set(true)
                            identity.set(macAppStoreIdentity)
                        }
                    }
                } else {
                    entitlementsFile.set(project.file("macos/developer-id-entitlements.plist"))
                    if (developerIdIdentity != null) {
                        signing {
                            sign.set(true)
                            identity.set(developerIdIdentity)
                        }
                    }
                    val appleId = System.getenv("APPLE_ID")
                    if (!appleId.isNullOrBlank()) {
                        notarization {
                            appleID.set(appleId)
                            password.set(System.getenv("APPLE_ID_PASSWORD"))
                            teamID.set(System.getenv("APPLE_TEAM_ID"))
                        }
                    }
                }
            }
            linux {
                iconFile.set(project.file("icons/logo.png"))
                packageName = "vasmarfas"
                debMaintainer = "vasmarfas@mail.ru"
                menuGroup = "Utility"
            }
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

val ffmpegDeb = listOf(
    "libpulse0", "libva2", "libva-drm2", "libva-x11-2", "libvdpau1", "libdrm2",
    "libxcb1", "libxcb-shm0", "libxcb-shape0", "libxcb-xfixes0", "libasound2t64 | libasound2",
)
val ffmpegRpm = listOf(
    "libpulse.so.0", "libva.so.2", "libva-drm.so.2", "libva-x11.so.2", "libvdpau.so.1", "libdrm.so.2",
    "libxcb.so.1", "libxcb-shm.so.0", "libxcb-shape.so.0", "libxcb-xfixes.so.0", "libasound.so.2",
).map { "$it()(64bit)" }

interface MacSigning {
    @get:Inject val exec: ExecOperations
}

if (macAppStore) {
    tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(unpackMacNatives) }
    tasks.withType<AbstractJPackageTask>().matching { it.name == "packagePkg" }.configureEach {
        freeArgs.addAll("--resource-dir", layout.buildDirectory.dir("compose/tmp/resources").get().asFile.path)
    }
}

if (macAppStore || developerIdIdentity != null) {
    val signing = objects.newInstance<MacSigning>()
    val appStore = macAppStore
    val programs = listOf("ffmpeg", "ffprobe")
    val helperEntitlements = file("macos/helper-entitlements.plist")
    val appEntitlements = file(if (appStore) "macos/entitlements.plist" else "macos/developer-id-entitlements.plist")
    val signer = if (appStore) {
        macAppStoreIdentity?.let {
            if (it.startsWith("3rd Party Mac Developer Application: ") || it.startsWith("Developer ID Application: ")) it
            else "3rd Party Mac Developer Application: $it"
        }
    } else {
        developerIdIdentity?.let { if (it.startsWith("Developer ID Application: ")) it else "Developer ID Application: $it" }
    }
    tasks.withType<AbstractJPackageTask>().matching { it.name == "createDistributableImpl" }.configureEach {
        val app = destinationDir.zip(packageName) { dir, name -> dir.dir("$name.app").asFile }
        doLast {
            fun codesign(target: File, entitlements: File?) = signing.exec.exec {
                commandLine(
                    listOfNotNull(
                        "codesign", "--force", "--options", "runtime", "--timestamp".takeIf { signer != null },
                        "--prefix", "com.vasmarfas.mobitool.",
                        "--entitlements".takeIf { entitlements != null }, entitlements?.path,
                        "--sign", signer ?: "-", target.path,
                    ),
                )
            }
            val bundle = app.get()
            if (appStore) {
                val resources = bundle.resolve("Contents/app/resources")
                programs.map { resources.resolve(it) }.forEach {
                    it.setExecutable(true, false)
                    codesign(it, helperEntitlements)
                }
                resources.listFiles { file -> file.extension == "jnilib" }.orEmpty().forEach { codesign(it, null) }
            } else {
                bundle.resolve("Contents/app").listFiles { file -> file.extension == "jar" }.orEmpty().forEach { jar ->
                    val entries = ZipFile(jar).use { zip ->
                        zip.entries().toList().map { it.name }.filter { it.substringAfterLast('/') in programs }
                    }
                    if (entries.isEmpty()) return@forEach
                    FileSystems.newFileSystem(jar.toPath()).use { fs ->
                        entries.forEach { entry ->
                            val program = temporaryDir.resolve(entry.substringAfterLast('/'))
                            Files.copy(fs.getPath(entry), program.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            codesign(program, null)
                            Files.copy(program.toPath(), fs.getPath(entry), StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
            }
            codesign(bundle, appEntitlements)
        }
    }
}

tasks.withType<AbstractJPackageTask>().configureEach {
    when (targetFormat) {
        TargetFormat.Deb -> freeArgs.addAll("--linux-package-deps", ffmpegDeb.joinToString(", "))
        TargetFormat.Rpm -> freeArgs.addAll("--linux-package-deps", ffmpegRpm.joinToString(", "))
        else -> {}
    }
}
