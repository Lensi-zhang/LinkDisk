package com.linkdisk.app.server

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

class FrpcProcess(
    context: Context,
    private val scope: CoroutineScope,
    private val onOutput: (String) -> Unit = {},
    private val onExit: (exitCode: Int, output: String) -> Unit = { _, _ -> },
) : Closeable {
    private val appContext = context.applicationContext
    private val processes = mutableSetOf<Process>()
    private val monitorJobs = mutableMapOf<Process, Job>()
    private val configFiles = mutableMapOf<Process, File>()
    private val logBuffers = mutableMapOf<Process, LogBuffer>()
    private val expectedStops = mutableSetOf<Process>()

    @Synchronized
    fun start(config: TunnelConfig) {
        check(processes.isEmpty()) { "frpc 已经在运行" }
        config.validate()

        val executable = File(appContext.applicationInfo.nativeLibraryDir, config.executableName)
        require(executable.isFile) { "当前安装包不包含 ${config.executableName}" }
        val runtimeDirectory = File(appContext.noBackupFilesDir, RUNTIME_DIRECTORY)
        check(runtimeDirectory.isDirectory || runtimeDirectory.mkdirs()) { "无法创建 frpc 运行目录" }
        val configFile = File(runtimeDirectory, "frpc.${config.runtimeConfigExtension}")
        configFile.writeText(config.runtimeConfig(), Charsets.UTF_8)

        val process = try {
            ProcessBuilder(
                executable.absolutePath,
                "-c",
                configFile.absolutePath,
            ).start()
        } catch (error: Exception) {
            configFile.delete()
            throw error
        }

        val logBuffer = LogBuffer(config.sensitiveValues)
        processes += process
        configFiles[process] = configFile
        logBuffers[process] = logBuffer
        monitorJobs[process] = scope.launch {
            monitor(process, configFile, logBuffer)
        }
    }

    @Synchronized
    fun stop() {
        if (processes.isEmpty()) return

        expectedStops += processes
        monitorJobs.values.forEach { it.cancel() }
        processes.forEach { process ->
            process.destroy()
            closeStreams(process)
            configFiles[process]?.delete()
        }
        processes.clear()
        monitorJobs.clear()
        configFiles.clear()
        logBuffers.clear()
    }

    override fun close() = stop()

    private suspend fun monitor(process: Process, configFile: File, logBuffer: LogBuffer) {
        var exitCode: Int? = null
        var failure: String? = null
        try {
            coroutineScope {
                launch(Dispatchers.IO) { consume(process.inputStream, logBuffer) }
                launch(Dispatchers.IO) { consume(process.errorStream, logBuffer) }
                exitCode = withContext(Dispatchers.IO) { process.waitFor() }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            failure = logBuffer.redact(error.message ?: "frpc 进程监控失败")
        } finally {
            withContext(NonCancellable) {
                process.destroy()
                closeStreams(process)
                configFile.delete()
                val expectedStop = synchronized(this@FrpcProcess) {
                    processes.remove(process)
                    monitorJobs.remove(process)
                    configFiles.remove(process)
                    logBuffers.remove(process)
                    expectedStops.remove(process)
                }
                val completedExitCode = exitCode
                if (!expectedStop && (completedExitCode != null || failure != null)) {
                    val output = logBuffer.snapshot().ifBlank {
                        failure ?: "frpc 已退出，退出码 $completedExitCode"
                    }
                    onExit(completedExitCode ?: -1, output)
                }
            }
        }
    }

    private fun consume(input: InputStream, logBuffer: LogBuffer) {
        try {
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val sanitized = logBuffer.redact(line).take(MAX_LOG_LINE_CHARS)
                    logBuffer.append(sanitized)
                    onOutput(sanitized)
                }
            }
        } catch (_: IOException) {
            // Process teardown closes both streams; waitFor still reports the actual exit status.
        }
    }

    private fun closeStreams(process: Process) {
        listOf(process.inputStream, process.errorStream, process.outputStream).forEach { stream ->
            try {
                stream.close()
            } catch (_: IOException) {
                // The process may already have closed the stream.
            }
        }
    }

    private class LogBuffer(private val secrets: List<String>) {
        private val lines = ArrayDeque<String>()

        @Synchronized
        fun append(line: String) {
            if (lines.size == MAX_LOG_LINES) lines.removeFirst()
            lines.addLast(line)
        }

        @Synchronized
        fun snapshot(): String = lines.joinToString("\n")

        fun redact(value: String): String {
            var result = ANSI_ESCAPE_SEQUENCE.replace(value, "")
            secrets.asSequence()
                .filter { it.isNotEmpty() }
                .sortedByDescending { it.length }
                .forEach { secret -> result = result.replace(secret, REDACTED) }
            return result
        }
    }

    private companion object {
        const val RUNTIME_DIRECTORY = "frpc"
        const val MAX_LOG_LINES = 40
        const val MAX_LOG_LINE_CHARS = 4096
        const val REDACTED = "<redacted>"
        private val ANSI_ESCAPE_SEQUENCE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    }
}
