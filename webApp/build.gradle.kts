import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("app")
        browser {
            commonWebpackConfig {
                outputFileName = "app.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.ui)
            implementation(libs.compose.runtime)
            implementation(libs.navigation.compose)
            implementation(libs.kotlinx.browser)
            implementation(npm("mediabunny", libs.versions.mediabunny.get()))
            implementation(npm("@mediabunny/mp3-encoder", libs.versions.mediabunny.get()))
            implementation(npm("pdfjs-dist", libs.versions.pdfjs.get()))
            implementation(npm("zxing-wasm", libs.versions.zxingWasm.get()))
        }
        wasmJsMain.get().resources.srcDir(layout.buildDirectory.dir("vendor"))
    }
}

// media, PDF and barcode libraries load on demand from vendor/ and stay out of the app bundle
val vendorScripts = tasks.register<Sync>("vendorScripts") {
    dependsOn(":kotlinWasmNpmInstall")
    val modules = rootProject.layout.buildDirectory.dir("wasm/node_modules")
    from(modules.map { it.dir("mediabunny/dist/bundles") }) { include("mediabunny.min.mjs") }
    from(modules.map { it.dir("@mediabunny/mp3-encoder/dist/bundles") }) { include("mediabunny-mp3-encoder.min.mjs") }
    from(modules.map { it.dir("zxing-wasm/dist") }) {
        include("es/**", "reader/zxing_reader.wasm")
        into("zxing")
    }
    from(modules.map { it.dir("pdfjs-dist") }) {
        include("build/pdf.min.mjs", "build/pdf.worker.min.mjs", "cmaps/**", "standard_fonts/**", "wasm/**", "iccs/**")
        into("pdfjs")
    }
    into(layout.buildDirectory.dir("vendor/vendor"))
}

// catalog size for the static pages, counted from the category lists at build time
val catalogDir = rootProject.layout.projectDirectory.dir("shared/src/commonMain/kotlin/com/vasmarfas/card/tools")
val toolCount = catalogDir.asFile.listFiles().orEmpty().filter { it.isDirectory }.sumOf { dir ->
    dir.listFiles { file -> file.name.endsWith("Tools.kt") }.orEmpty().sumOf { file ->
        providers.fileContents(catalogDir.file("${dir.name}/${file.name}")).asText.get()
            .substringAfter("List<Tool> = listOf(", "")
            .substringBefore(")")
            .lines()
            .count { it.trim().endsWith("Tool,") }
    }
}
val categoryCount = providers.fileContents(catalogDir.file("Tool.kt")).asText.get()
    .substringAfter("enum class ToolCategory")
    .substringBefore(";")
    .lines()
    .count { Regex("""^\s+[A-Z_]+\(""").containsMatchIn(it) }

fun russianCount(n: Int, one: String, few: String, many: String): String = "$n " + when {
    n % 10 == 1 && n % 100 != 11 -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}

apply(from = "prerender.gradle.kts")

val catalogTokens = mapOf(
    "@TOOLS_RU@" to russianCount(toolCount, "инструмент", "инструмента", "инструментов"),
    "@TOOLS_EN@" to "$toolCount tools",
    "@CATEGORIES_RU@" to "в $categoryCount " + if (categoryCount % 10 == 1 && categoryCount % 100 != 11) "категории" else "категориях",
    "@CATEGORIES_EN@" to "$categoryCount categories",
    "    <!-- profile -->" to extra["prerenderedProfile"] as String,
)

tasks.named<ProcessResources>("wasmJsProcessResources") {
    dependsOn(vendorScripts)
    val tokens = catalogTokens
    inputs.property("catalog", tokens.toString())
    filesMatching(listOf("index.html", "download.html", "manifest.webmanifest")) {
        filter { line -> tokens.entries.fold(line) { text, (token, value) -> text.replace(token, value) } }
    }
}

// the splash bar needs unpacked sizes, a compressed response does not give them
listOf("wasmJsBrowserDistribution" to "productionExecutable", "wasmJsBrowserDevelopmentExecutableDistribution" to "developmentExecutable")
    .forEach { (distribution, variant) ->
        val dist = layout.buildDirectory.dir("dist/wasmJs/$variant")
        val hints = tasks.register("${distribution}WasmHints") {
            doLast {
                val dir = dist.get().asFile
                val wasm = dir.listFiles { file -> file.extension == "wasm" }.orEmpty().sortedBy { it.name }
                val index = dir.resolve("index.html")
                if (wasm.isEmpty() || !index.exists()) return@doLast
                val links = wasm.joinToString("\n") { """    <link rel="preload" href="${it.name}" as="fetch" type="application/wasm" crossorigin>""" }
                val sizes = wasm.joinToString(", ") { "\"${it.name}\": ${it.length()}" }
                index.writeText(index.readText().replace("    <!-- wasm hints -->", "$links\n    <script>window.vasmarfasWasmSizes = { $sizes };</script>"))
            }
        }
        tasks.matching { it.name == distribution }.configureEach { finalizedBy(hints) }
    }
