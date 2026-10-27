import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.crashlytics) apply false
    alias(libs.plugins.firebasePerf) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// the pipeline overrides the version through the environment
val appVersionName: String = System.getenv("APP_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.3.0"

// Play and App Store Connect take only a growing integer: 1.4.12-rc2 packs into 1041202, the final 1.4.12 into 1041299
val appVersionCode: Int = System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: run {
    val (major, minor, patch) = appVersionName.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    val stage = if ('-' in appVersionName) appVersionName.takeLastWhile { it.isDigit() }.toIntOrNull() ?: 0 else 99
    ((major * 100 + minor) * 100 + patch) * 100 + stage
}

extra["appVersionName"] = appVersionName
extra["appVersionCode"] = appVersionCode

// Xcode reads the version from the xcconfig, so it is rewritten before the framework links
tasks.register("syncIosVersion") {
    val config = layout.projectDirectory.file("iosApp/Configuration/Config.xcconfig").asFile
    val marketing = appVersionName.substringBefore('-')
    val build = appVersionCode
    inputs.property("version", "$marketing+$build")
    outputs.file(config)
    doLast {
        val updated = config.readLines().joinToString("\n") { line ->
            when {
                line.startsWith("MARKETING_VERSION=") -> "MARKETING_VERSION=$marketing"
                line.startsWith("CURRENT_PROJECT_VERSION=") -> "CURRENT_PROJECT_VERSION=$build"
                else -> line
            }
        }
        if (config.readText() != updated) config.writeText(updated)
    }
}

abstract class TaskTimes : BuildService<TaskTimes.Params>, OperationCompletionListener, AutoCloseable {
    interface Params : BuildServiceParameters {
        val tasks: Property<String>
    }

    private val times = ConcurrentHashMap<String, Long>()

    override fun onFinish(event: FinishEvent) {
        if (event is TaskFinishEvent) times[event.descriptor.taskPath] = event.result.endTime - event.result.startTime
    }

    override fun close() {
        val slow = times.entries.filter { it.value >= 5_000 }.sortedByDescending { it.value }.take(20)
        if (slow.isEmpty()) return
        fun span(ms: Long) = (ms / 1000).let {
            if (it >= 3600) "%dh %02dm %02ds".format(it / 3600, it / 60 % 60, it % 60) else "%dm %02ds".format(it / 60, it % 60)
        }
        val tasks = parameters.tasks.get()
        println("Slowest tasks of $tasks:")
        slow.forEach { println("${span(it.value).padStart(13)}  ${it.key}") }
        System.getenv("GITHUB_STEP_SUMMARY")?.let { summary ->
            File(summary).appendText(buildString {
                appendLine("#### Gradle `$tasks`")
                appendLine()
                appendLine("| Task | Time |")
                appendLine("|---|---|")
                slow.forEach { appendLine("| `${it.key}` | ${span(it.value)} |") }
                appendLine()
            })
        }
    }
}

abstract class TaskTimesPlugin @Inject constructor(private val events: BuildEventsListenerRegistry) : Plugin<Project> {
    override fun apply(project: Project) {
        events.onTaskCompletion(project.gradle.sharedServices.registerIfAbsent("taskTimes", TaskTimes::class) {
            parameters.tasks.set(project.gradle.startParameter.taskNames.joinToString(" "))
        })
    }
}

// on CI every build lists its slowest tasks, including the one Xcode starts inside the iOS archive
if (System.getenv("GITHUB_ACTIONS") == "true") apply<TaskTimesPlugin>()
