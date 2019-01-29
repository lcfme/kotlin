/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.report

import org.gradle.BuildAdapter
import org.gradle.BuildResult
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.TaskState
import org.jetbrains.kotlin.compilerRunner.GradleCompilerRunner
import org.jetbrains.kotlin.gradle.plugin.internal.state.TaskExecutionResults
import java.io.File
import java.lang.StringBuilder
import kotlin.math.max

internal class KotlinPerformanceReporter(
    private val gradle: Gradle,
    private val perfReportFile: File
) : BuildAdapter(), TaskExecutionListener {
    init {
        val dir = perfReportFile.parentFile
        check(dir.isDirectory) { "$dir does not exist or is a file" }
        check(!perfReportFile.isFile) { "Performance report log file $perfReportFile exists already" }
    }

    private val taskStartNs = HashMap<Task, Long>()
    private val kotlinTaskTimeNs = HashMap<Task, Long>()
    private val tasksSb = StringBuilder()

    @Volatile
    private var allTasksTimeNs: Long = 0L

    @Synchronized
    override fun beforeExecute(task: Task) {
        taskStartNs[task] = System.nanoTime()
    }

    @Synchronized
    override fun afterExecute(task: Task, state: TaskState) {
        val startNs = taskStartNs[task] ?: return

        val endNs = System.nanoTime()
        val timeNs = endNs - startNs
        allTasksTimeNs += timeNs

        if (!task.javaClass.name.startsWith("org.jetbrains.kotlin")) {
            return
        }

        kotlinTaskTimeNs[task] = timeNs
        tasksSb.appendln()

        val skipMessage = state.skipMessage
        if (skipMessage != null) {
            tasksSb.appendln("$task was skipped: $skipMessage")
        } else {
            tasksSb.appendln("$task executed in ${formatTime(timeNs)}")
        }

        val path = task.path
        val executionResult = TaskExecutionResults[path]
        if (executionResult != null) {
            tasksSb.appendln("Execution strategy: ${executionResult.executionStrategy}")

            executionResult.icLogLines?.let { lines ->
                tasksSb.appendln("Compilation log for $task:")
                lines.forEach { tasksSb.appendln("  $it") }
            }
        }
    }

    @Synchronized
    override fun buildFinished(result: BuildResult) {
        val logger = result.gradle?.rootProject?.logger
        try {
            perfReportFile.writeText(taskOverview() + tasksSb.toString() + moduleInfo())
            logger?.lifecycle("Kotlin performance report is written to ${perfReportFile.canonicalPath}")
        } catch (e: Throwable) {
            logger?.error("Could not write Kotlin performance report to ${perfReportFile.canonicalPath}", e)
        }
    }

    private fun taskOverview(): String {
        val sb = StringBuilder()
        val kotlinTotalTimeNs = kotlinTaskTimeNs.values.sum()
        val ktTaskPercent = (kotlinTotalTimeNs.toDouble() / allTasksTimeNs * 100).asString(1)

        sb.appendln("Total execution time for Kotlin tasks: ${formatTime(kotlinTotalTimeNs)} ($ktTaskPercent % of all tasks time)")

        val table = TextTable("Time", "% of Kotlin time", "Task")
        kotlinTaskTimeNs.entries
            .sortedByDescending { (_, timeNs) -> timeNs }
            .forEach { (task, timeNs) ->
                val percent = (timeNs.toDouble() / kotlinTotalTimeNs * 100).asString(1)
                table.addRow(formatTime(timeNs), "$percent %", task.path)
            }
        table.printTo(sb)
        sb.appendln()
        return sb.toString()
    }

    private fun moduleInfo(): String {
        val sb = StringBuilder()

        sb.appendln()
        sb.appendln("Units of compilation:")

        val modulesInfo = GradleCompilerRunner.buildModulesInfo(gradle)
        modulesInfo.allModulesToFiles().forEach { (module, files) ->
            sb.appendln("  '${module.projectPath}:${module.name}'")
            sb.appendln("    build history: ${module.buildHistoryFile}")
            sb.appendln("    build dir: ${module.buildDir}")
            files.forEach { sb.appendln("      $it") }
        }

        return sb.toString()
    }

    private fun formatTime(ns: Long): String {
        val seconds = ns.toDouble() / 1_000_000_000
        return seconds.asString(2) + " s"
    }

    private fun Double.asString(decPoints: Int): String =
        String.format("%.${decPoints}f", this)

    private class TextTable(vararg columnNames: String) {
        private val rows = ArrayList<List<String>>()
        private val columnsCount = columnNames.size
        private val maxLengths = IntArray(columnsCount) { columnNames[it].length }

        init {
            rows.add(columnNames.toList())
        }

        fun addRow(vararg row: String) {
            check(row.size == columnsCount) { "Row size ${row.size} differs from columns count $columnsCount" }
            rows.add(row.toList())

            for ((i, col) in row.withIndex()) {
                maxLengths[i] = max(maxLengths[i], col.length)
            }
        }

        fun printTo(sb: StringBuilder) {
            for (row in rows) {
                sb.appendln()
                for ((i, col) in row.withIndex()) {
                    if (i > 0) sb.append("|")

                    sb.append(col.padEnd(maxLengths[i], ' '))
                }
            }
        }
    }
}

