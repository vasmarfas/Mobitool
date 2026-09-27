import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// FFmpeg ships per OS and every desktop package is built on its own OS, so the host decides
val javacppPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        else -> a
    }
    when {
        os.contains("win") -> "windows-$arch"
        os.contains("mac") -> "macosx-$arch"
        else -> "linux-$arch"
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    // the Compose swiftUtils klib links against the Xcode path of JetBrains' CI, so test executables
    // get the Swift libraries of whichever Xcode is selected here
    if (System.getProperty("os.name").startsWith("Mac")) {
        val xcode = providers.exec { commandLine("xcode-select", "-p") }.standardOutput.asText.map { it.trim() }
        iosSimulatorArm64().binaries.withType<TestExecutable>().configureEach {
            linkerOpts("-L${xcode.get()}/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/iphonesimulator")
        }
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
        compilerOptions {
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    android {
        namespace = "com.vasmarfas.card.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
    }

    sourceSets {
        val nonWebMain = create("nonWebMain") {
            dependsOn(commonMain.get())
        }
        val skikoMain = create("skikoMain") {
            dependsOn(commonMain.get())
        }
        val jvmCommonMain = create("jvmCommonMain") {
            dependsOn(nonWebMain)
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.zxing.core)
            }
        }
        jvmMain.get().dependsOn(jvmCommonMain)
        jvmMain.get().dependsOn(skikoMain)
        androidMain.get().dependsOn(jvmCommonMain)
        wasmJsMain.get().dependsOn(skikoMain)

        // the dependsOn edges above switch off the default hierarchy template, so the Apple
        // source set has to be attached by hand or nothing in iosMain reaches a compilation
        val appleShared = maybeCreate("iosMain")
        appleShared.dependsOn(nonWebMain)
        appleShared.dependsOn(skikoMain)
        getByName("iosArm64Main").dependsOn(appleShared)
        getByName("iosSimulatorArm64Main").dependsOn(appleShared)

        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.analytics)
            implementation(libs.firebase.crashlytics)
            implementation(libs.firebase.perf)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.media3.transformer)
            implementation(libs.media3.effect)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.compose)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.navigationevent.compose)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation.compose)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.ktor.client.core)
            implementation(libs.materialKolor)
            implementation(libs.qrose)
            implementation(libs.qrose.oned)
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs)
        }
        jvmTest.dependencies {
            implementation(libs.compose.uiTestJunit4)
            implementation(libs.commons.imaging)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.bytedeco.javacpp)
            implementation(libs.bytedeco.ffmpeg)
            implementation("org.bytedeco:javacpp:${libs.versions.javacpp.get()}:$javacppPlatform")
            implementation("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.get()}:$javacppPlatform")
            implementation(libs.pdfbox)
            implementation(libs.jna)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.kotlinx.browser)
        }
    }
}


// Compose Resources resolves only the active locale, a search that matches both languages needs its own copy
val generateStringIndex = tasks.register("generateStringIndex") {
    val english = layout.projectDirectory.file("src/commonMain/composeResources/values/strings.xml").asFile
    val russian = layout.projectDirectory.file("src/commonMain/composeResources/values-ru/strings.xml").asFile
    val outputDir = layout.buildDirectory.dir("generated/stringIndex/kotlin").get().asFile
    inputs.files(english, russian)
    outputs.dir(outputDir)
    doLast {
        fun parse(file: File): List<Pair<String, String>> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).map { index ->
                val node = nodes.item(index) as org.w3c.dom.Element
                // mirror what the resource loader does: backslash sequences are unescaped,
                // quotes are not
                val raw = node.textContent
                val text = StringBuilder(raw.length).also { sb ->
                    var at = 0
                    while (at < raw.length) {
                        val c = raw[at]
                        if (c == '\\' && at + 1 < raw.length) {
                            when (val next = raw[at + 1]) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                '\\' -> sb.append('\\')
                                else -> sb.append(c).append(next)
                            }
                            at += 2
                        } else {
                            sb.append(c)
                            at++
                        }
                    }
                }.toString()
                node.getAttribute("name") to text
            }
        }

        fun literal(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\r", "")

        val target = File(outputDir, "com/vasmarfas/card/resources")
        target.mkdirs()
        val chunkSize = 300
        val out = StringBuilder()
        out.appendLine("// Generated by generateStringIndex from composeResources/values*/strings.xml.")
        out.appendLine("package com.vasmarfas.card.resources")
        out.appendLine()
        out.appendLine("import org.jetbrains.compose.resources.StringResource")
        out.appendLine()
        listOf("en" to parse(english), "ru" to parse(russian)).forEach { (name, rows) ->
            val chunks = rows.chunked(chunkSize)
            chunks.forEachIndexed { index, chunk ->
                out.appendLine("private fun $name$index(m: MutableMap<String, String>) {")
                chunk.forEach { (key, text) ->
                    out.appendLine("    m[\"$key\"] = \"${literal(text)}\"")
                }
                out.appendLine("}")
            }
            val calls = chunks.indices.joinToString("; ") { "$name$it(it)" }
            out.appendLine("private val $name: Map<String, String> by lazy { HashMap<String, String>(${rows.size}).also { $calls } }")
            out.appendLine()
        }
        out.appendLine("fun StringResource.english(): String? = en[key]")
        out.appendLine()
        out.appendLine("fun StringResource.russian(): String? = ru[key]")
        out.appendLine()
        out.appendLine("fun StringResource.matches(query: String): Boolean =")
        out.appendLine("    english()?.contains(query, ignoreCase = true) == true ||")
        out.appendLine("        russian()?.contains(query, ignoreCase = true) == true")
        File(target, "StringIndex.kt").writeText(out.toString())
    }
}

kotlin.sourceSets.commonMain.get().kotlin.srcDir(generateStringIndex)

val generateAppVersion = tasks.register("generateAppVersion") {
    val version = rootProject.extra["appVersionName"] as String
    val outputDir = layout.buildDirectory.dir("generated/version/kotlin").get().asFile
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val target = File(outputDir, "com/vasmarfas/card/core")
        target.mkdirs()
        val out = StringBuilder()
        out.appendLine("// Generated by generateAppVersion from the root build script.")
        out.appendLine("package com.vasmarfas.card.core")
        out.appendLine()
        out.appendLine("const val APP_VERSION: String = \"$version\"")
        File(target, "AppVersion.kt").writeText(out.toString())
    }
}

kotlin.sourceSets.commonMain.get().kotlin.srcDir(generateAppVersion)

val generateDesktopMetrica = tasks.register("generateDesktopMetrica") {
    val counter = System.getenv("METRICA_DESKTOP_COUNTER").orEmpty()
    val token = System.getenv("METRICA_DESKTOP_TOKEN").orEmpty()
    val outputDir = layout.buildDirectory.dir("generated/desktopMetrica/kotlin").get().asFile
    inputs.property("counter", counter)
    inputs.property("token", token)
    outputs.dir(outputDir)
    doLast {
        val target = File(outputDir, "com/vasmarfas/card/core")
        target.mkdirs()
        val out = StringBuilder()
        out.appendLine("// Generated by generateDesktopMetrica from the shared build script.")
        out.appendLine("package com.vasmarfas.card.core")
        out.appendLine()
        out.appendLine("internal const val METRICA_DESKTOP_COUNTER: String = \"$counter\"")
        out.appendLine("internal const val METRICA_DESKTOP_TOKEN: String = \"$token\"")
        File(target, "DesktopMetrica.kt").writeText(out.toString())
    }
}

kotlin.sourceSets.named("jvmMain") { kotlin.srcDir(generateDesktopMetrica) }

// Xcode picks the version up from the xcconfig, which the root task rewrites
tasks.matching { it.name.contains("Framework") }.configureEach {
    dependsOn(rootProject.tasks.named("syncIosVersion"))
}

// commons-logging would pick Ktor's SLF4J and drop the PDFBox warnings the tests assert on
tasks.named<Test>("jvmTest") {
    systemProperty("org.apache.commons.logging.LogFactory", "org.apache.commons.logging.impl.LogFactoryImpl")
    systemProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.Jdk14Logger")
    // store screenshots render only when asked: ./gradlew :shared:jvmTest --tests "*StoreShots*" -PstoreShots=<folder>
    providers.gradleProperty("storeShots").orNull?.let { systemProperty("store.shots", it) }
    providers.gradleProperty("storeOnly").orNull?.let { systemProperty("store.only", it) }
    providers.gradleProperty("storePick").orNull?.let { systemProperty("store.pick", it) }
}

apply(from = "mac-vendors.gradle.kts")

compose.resources {
    publicResClass = true
    packageOfResClass = "com.vasmarfas.card.resources"
    generateResClass = always
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
