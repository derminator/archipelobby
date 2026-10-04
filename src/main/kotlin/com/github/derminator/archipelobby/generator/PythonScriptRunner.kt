package com.github.derminator.archipelobby.generator

import com.sun.jna.Native
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.ByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

import org.jetbrains.annotations.Blocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Component
class PythonScriptRunner(
    @Value($$"${archipelobby.python.executable:python}") private val pythonExecutable: String = "python",
    @Value($$"${archipelobby.resources.python-timeout-seconds:300}") private val timeoutSeconds: Long = 300,
    @Value($$"${archipelobby.resources.max-python-output-bytes:1048576}") private val maxOutputBytes: Long = 1024 * 1024,
    @Value($$"${archipelobby.resources.max-python-descendants:256}") private val maxDescendants: Int = 256,
) {

    private val logger = LoggerFactory.getLogger(PythonScriptRunner::class.java)
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    init {
        require(timeoutSeconds > 0)
        require(maxOutputBytes > 0)
        require(maxDescendants > 0)
    }

    private fun spawn(
        scriptPath: String,
        args: Array<out String>,
        extraEnv: Map<String, String> = emptyMap(),
        isolatedProcessGroup: Boolean = false,
    ): Process {
        val scriptFile = File(scriptPath).absoluteFile
        val command = mutableListOf<String>()
        if (isolatedProcessGroup && !isWindows) {
            command += listOf(
                pythonExecutable,
                "-c",
                "import os,sys; os.setsid(); os.execv(sys.executable,[sys.executable,*sys.argv[1:]])",
            )
        } else {
            command += pythonExecutable
        }
        command += scriptFile.path
        command.addAll(args)

        val environment = System.getenv().toMutableMap().also {
            it["PYTHONUNBUFFERED"] = "1"
            it["DISPLAY"] = ""
            it["PYTHONWARNINGS"] = "ignore"
            it.putAll(extraEnv)
        }
        if (isWindows) {
            return WindowsNativeProcess.start(command, environment, maxDescendants)
        }

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .also { it.environment().putAll(environment) }
            .start()
        process.outputStream.close()
        return process
    }

    /** Executes a bounded Python subprocess and captures its merged output. */
    @Blocking
    fun run(scriptPath: String, vararg args: String): String {
        val process = spawn(scriptPath, args, isolatedProcessGroup = true)
        val knownDescendants = ConcurrentHashMap.newKeySet<ProcessHandle>()
        val cleanupDeadline = AtomicLong(0)
        val watcherFailure = AtomicReference<ResponseStatusException?>()
        val watcherExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "archipelobby-python-tree").also { it.isDaemon = true }
        }
        val watcherFuture = watcherExecutor.submit {
            fun captureDescendants(): Boolean {
                if (process is WindowsNativeProcess && process.descendantLimitExceeded()) {
                    watcherFailure.compareAndSet(
                        null,
                        ResponseStatusException(
                            HttpStatus.CONTENT_TOO_LARGE,
                            "Python descendant process limit of $maxDescendants exceeded",
                        ),
                    )
                    signalProcessTree(process, sharedCleanupDeadline(cleanupDeadline))
                    return false
                }
                if (isWindows) return true
                val snapshot = process.toHandle().descendants()
                    .limit(maxDescendants.toLong() + 1)
                    .toList()
                val unseen = snapshot.filterNot(knownDescendants::contains)
                if (snapshot.size > maxDescendants || unseen.size > maxDescendants - knownDescendants.size) {
                    watcherFailure.compareAndSet(
                        null,
                        ResponseStatusException(
                            HttpStatus.CONTENT_TOO_LARGE,
                            "Python descendant process limit of $maxDescendants exceeded",
                        ),
                    )
                    (knownDescendants + snapshot).forEach { it.destroyForcibly() }
                    signalProcessTree(process, sharedCleanupDeadline(cleanupDeadline))
                    return false
                }
                knownDescendants.addAll(snapshot)
                return true
            }
            while (process.isAlive && !Thread.currentThread().isInterrupted) {
                if (!captureDescendants()) return@submit
                Thread.sleep(5)
            }
            captureDescendants()
        }
        val readerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "archipelobby-python-output").also { it.isDaemon = true }
        }
        val outputFuture = readerExecutor.submit<String> {
            val output = ByteArrayOutputStream()
            var outputBytes = 0L
            process.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count.toLong() > maxOutputBytes - outputBytes) {
                        signalProcessTree(process, sharedCleanupDeadline(cleanupDeadline))
                        throw ResponseStatusException(
                            HttpStatus.CONTENT_TOO_LARGE,
                            "Python script output limit of $maxOutputBytes bytes exceeded",
                        )
                    }
                    outputBytes += count
                    output.write(buffer, 0, count)
                    String(buffer, 0, count, Charsets.UTF_8)
                        .lineSequence()
                        .filter { it.isNotEmpty() }
                        .forEach { logger.info("[python] {}", it) }
                }
            }
            output.toString(Charsets.UTF_8)
        }

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                sharedCleanupDeadline(cleanupDeadline)
                outputFuture.cancel(true)
                throw ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "Python script timed out after $timeoutSeconds seconds",
                )
            }
            watcherFailure.get()?.let { throw it }

            val outputDeadline = sharedCleanupDeadline(cleanupDeadline)
            val captured = try {
                val remaining = remainingCleanupNanos(outputDeadline)
                    ?: throw TimeoutException("Python cleanup deadline expired")
                outputFuture.get(remaining, TimeUnit.NANOSECONDS)
            } catch (error: ExecutionException) {
                val cause = error.cause
                if (cause is ResponseStatusException) throw cause
                throw error
            } catch (error: TimeoutException) {
                sharedCleanupDeadline(cleanupDeadline)
                outputFuture.cancel(true)
                throw ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "Python script output did not close after the process exited",
                    error,
                )
            }
            val watcherRemaining = remainingCleanupNanos(outputDeadline)
                ?: throw ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "Python process cleanup deadline expired before descendant tracking completed",
                )
            try {
                watcherFuture.get(watcherRemaining, TimeUnit.NANOSECONDS)
            } catch (error: TimeoutException) {
                throw ResponseStatusException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "Python descendant tracking did not finish within the cleanup deadline",
                    error,
                )
            }
            watcherFailure.get()?.let { throw it }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Python script failed (exit $exitCode): ${captured.trim()}",
                )
            }
            return captured
        } finally {
            val deadline = sharedCleanupDeadline(cleanupDeadline)
            watcherFuture.cancel(true)
            watcherExecutor.shutdownNow()
            remainingCleanupNanos(deadline)?.let {
                watcherExecutor.awaitTermination(minOf(it, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS)
            }
            destroyProcessTree(process, knownDescendants, deadline)
            readerExecutor.shutdownNow()
            remainingCleanupNanos(deadline)?.let {
                readerExecutor.awaitTermination(it, TimeUnit.NANOSECONDS)
            }
            (process as? WindowsNativeProcess)?.close()
        }
    }

    /**
     * Spawns a Python script as a long-running background subprocess. The caller
     * owns the returned Process and is responsible for its lifecycle.
     */
    fun runInBackground(scriptPath: String, extraEnv: Map<String, String> = emptyMap(), vararg args: String): Process =
        spawn(scriptPath, args, extraEnv)

    private fun sharedCleanupDeadline(deadline: AtomicLong): Long {
        val existing = deadline.get()
        if (existing != 0L) return existing
        val proposed = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        return if (deadline.compareAndSet(0, proposed)) proposed else deadline.get()
    }

    private fun signalProcessTree(process: Process, cleanupDeadline: Long) {
        if (!isWindows) {
            runCatching {
                val killer = ProcessBuilder("kill", "-KILL", "--", "-${process.pid()}").start()
                remainingCleanupNanos(cleanupDeadline)?.let {
                    killer.waitFor(minOf(it, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS)
                }
            }
        }
        process.destroyForcibly()
    }

    private fun destroyProcessTree(
        process: Process,
        knownDescendants: Collection<ProcessHandle>,
        cleanupDeadline: Long,
    ) {
        if (isWindows) {
            signalProcessTree(process, cleanupDeadline)
            remainingCleanupNanos(cleanupDeadline)?.let { process.waitFor(it, TimeUnit.NANOSECONDS) }
            return
        }
        val descendants = knownDescendants.toMutableSet()
        descendants += process.toHandle().descendants().limit(maxDescendants.toLong()).toList()
        signalProcessTree(process, cleanupDeadline)
        descendants += process.toHandle().descendants().limit(maxDescendants.toLong()).toList()
        descendants.forEach { it.destroyForcibly() }
        remainingCleanupNanos(cleanupDeadline)?.let { process.waitFor(it, TimeUnit.NANOSECONDS) }
        descendants.forEach { descendant ->
            if (descendant.isAlive) descendant.destroyForcibly()
            val remaining = remainingCleanupNanos(cleanupDeadline) ?: return@forEach
            runCatching { descendant.onExit().get(remaining, TimeUnit.NANOSECONDS) }
        }
    }

    private fun remainingCleanupNanos(deadline: Long): Long? =
        (deadline - System.nanoTime()).takeIf { it > 0 }
}

internal fun resolveWindowsExecutable(
    executable: String,
    pathEnvironment: String = System.getenv("PATH").orEmpty(),
): Path {
    require(executable.isNotBlank() && '\u0000' !in executable) { "Python executable is invalid" }
    val configured = Path.of(executable)
    fun accepted(path: Path): Path? {
        val absolute = path.toAbsolutePath().normalize()
        return absolute.takeIf {
            Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it)
        }
    }
    if (configured.isAbsolute) {
        return accepted(configured)
            ?: throw IllegalStateException("Configured Python executable is not a regular file: $configured")
    }
    require(configured.parent == null) {
        "Relative Windows Python executable paths must be resolved to an absolute path"
    }
    val extensions = if (configured.fileName.toString().contains('.')) listOf("") else listOf(".exe", ".com")
    for (directoryValue in pathEnvironment.split(';')) {
        val directory = directoryValue.trim().trim('"')
        if (directory.isEmpty()) continue
        for (extension in extensions) {
            accepted(Path.of(directory).resolve(executable + extension))?.let { return it }
        }
    }
    throw IllegalStateException("Unable to resolve the configured Python executable on PATH: $executable")
}

private class WindowsProcessJob private constructor(
    private val handle: WinNT.HANDLE,
    private val completionPort: WinNT.HANDLE,
    private val maxDescendants: Int,
) : AutoCloseable {
    private var observedProcesses = 0L
    private var limitExceeded = false

    @Synchronized
    fun descendantLimitExceeded(): Boolean {
        val message = IntByReference()
        val key = PointerByReference()
        val overlapped = PointerByReference()
        while (true) {
            if (!Kernel32Job.INSTANCE.GetQueuedCompletionStatus(
                    completionPort,
                    message,
                    key,
                    overlapped,
                    0,
                )
            ) {
                val error = Native.getLastError()
                if (error == WAIT_TIMEOUT) break
                throw IllegalStateException("GetQueuedCompletionStatus failed with Win32 error $error")
            }
            when (message.value) {
                JOB_OBJECT_MSG_ACTIVE_PROCESS_LIMIT -> limitExceeded = true
                JOB_OBJECT_MSG_NEW_PROCESS -> {
                    observedProcesses++
                    if (observedProcesses > maxDescendants.toLong() + 1) limitExceeded = true
                }
            }
        }
        return limitExceeded
    }

    override fun close() {
        val jobClosed = Kernel32.INSTANCE.CloseHandle(handle)
        val portClosed = Kernel32.INSTANCE.CloseHandle(completionPort)
        if (!jobClosed || !portClosed) {
            throw IllegalStateException("CloseHandle(job) failed with Win32 error ${Native.getLastError()}")
        }
    }

    companion object {
        private const val JOB_OBJECT_ASSOCIATE_COMPLETION_PORT_INFORMATION = 7
        private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
        private const val JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 0x00000008
        private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000
        private const val JOB_OBJECT_MSG_ACTIVE_PROCESS_LIMIT = 3
        private const val JOB_OBJECT_MSG_NEW_PROCESS = 6

        fun create(maxDescendants: Int): WindowsProcessJob {
            val kernel = Kernel32.INSTANCE
            val jobApi = Kernel32Job.INSTANCE
            val job = jobApi.CreateJobObjectW(null, null)
                ?: throw IllegalStateException("CreateJobObject failed with Win32 error ${Native.getLastError()}")
            val completionPort = jobApi.CreateIoCompletionPort(
                WinBase.INVALID_HANDLE_VALUE,
                null,
                BaseTSD.ULONG_PTR(0),
                1,
            ) ?: run {
                kernel.CloseHandle(job)
                throw IllegalStateException("CreateIoCompletionPort failed with Win32 error ${Native.getLastError()}")
            }
            try {
                val limits = JobObjectExtendedLimitInformation()
                val expectedLimitSize = if (Native.POINTER_SIZE == 8) 144 else 108
                check(limits.size() == expectedLimitSize) {
                    "Unexpected Windows Job Object structure size ${limits.size()} (expected $expectedLimitSize)"
                }
                limits.BasicLimitInformation.LimitFlags =
                    JOB_OBJECT_LIMIT_ACTIVE_PROCESS or JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                limits.BasicLimitInformation.ActiveProcessLimit = WinDef.DWORD(maxDescendants.toLong() + 1)
                limits.write()
                if (!jobApi.SetInformationJobObject(
                        job,
                        JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                        limits.pointer,
                        limits.size(),
                    )
                ) {
                    throw IllegalStateException(
                        "SetInformationJobObject failed with Win32 error ${Native.getLastError()}",
                    )
                }
                val association = JobObjectAssociateCompletionPort().also {
                    it.CompletionPort = completionPort
                    it.write()
                }
                if (!jobApi.SetInformationJobObject(
                        job,
                        JOB_OBJECT_ASSOCIATE_COMPLETION_PORT_INFORMATION,
                        association.pointer,
                        association.size(),
                    )
                ) {
                    throw IllegalStateException(
                        "Job completion-port association failed with Win32 error ${Native.getLastError()}",
                    )
                }
                return WindowsProcessJob(job, completionPort, maxDescendants)
            } catch (error: Throwable) {
                kernel.CloseHandle(job)
                kernel.CloseHandle(completionPort)
                throw error
            }
        }
    }

    fun assign(processHandle: WinNT.HANDLE) {
        if (!Kernel32Job.INSTANCE.AssignProcessToJobObject(handle, processHandle)) {
            throw IllegalStateException(
                "AssignProcessToJobObject failed with Win32 error ${Native.getLastError()}",
            )
        }
    }
}

internal class WindowsNativeProcess private constructor(
    private val processHandle: WinNT.HANDLE,
    private val processId: Long,
    private val stdoutHandle: WinNT.HANDLE,
    private val job: WindowsProcessJob,
) : Process(), AutoCloseable {
    private val jobClosed = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val descendantViolation = AtomicBoolean()
    private val stdout = WindowsHandleInputStream(stdoutHandle)
    private val jobMonitor = Thread({
        try {
            while (!closed.get() && isAlive) {
                if (job.descendantLimitExceeded()) {
                    descendantViolation.set(true)
                    closeJob()
                    break
                }
                Thread.sleep(5)
            }
            if (!closed.get() && job.descendantLimitExceeded()) descendantViolation.set(true)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
            descendantViolation.set(true)
            runCatching { closeJob() }
        }
    }, "archipelobby-windows-job-$processId").also {
        it.isDaemon = true
        it.start()
    }

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun pid(): Long = processId
    override fun toHandle(): ProcessHandle = ProcessHandle.of(processId)
        .orElseThrow { IllegalStateException("Windows child process $processId is unavailable") }

    fun descendantLimitExceeded(): Boolean = descendantViolation.get()

    override fun waitFor(): Int {
        val result = Kernel32Job.INSTANCE.WaitForSingleObject(processHandle, INFINITE)
        if (result != WAIT_OBJECT_0) throw IllegalStateException("WaitForSingleObject failed: $result")
        return exitValue()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        require(timeout >= 0)
        val millis = unit.toMillis(timeout).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        return when (val result = Kernel32Job.INSTANCE.WaitForSingleObject(processHandle, millis)) {
            WAIT_OBJECT_0 -> true
            WAIT_TIMEOUT -> false
            else -> throw IllegalStateException("WaitForSingleObject failed: $result")
        }
    }

    override fun exitValue(): Int {
        val code = IntByReference()
        if (!Kernel32Job.INSTANCE.GetExitCodeProcess(processHandle, code)) {
            throw IllegalStateException("GetExitCodeProcess failed with Win32 error ${Native.getLastError()}")
        }
        if (code.value == STILL_ACTIVE) throw IllegalThreadStateException("Process has not exited")
        return code.value
    }

    override fun isAlive(): Boolean = runCatching { exitValue(); false }.getOrElse {
        if (it is IllegalThreadStateException) true else throw it
    }

    override fun destroy() {
        closeJob()
        Kernel32Job.INSTANCE.TerminateProcess(processHandle, 1)
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }

    private fun closeJob() {
        if (jobClosed.compareAndSet(false, true)) job.close()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            jobMonitor.interrupt()
            closeJob()
            stdout.close()
            Kernel32.INSTANCE.CloseHandle(processHandle)
        }
    }

    companion object {
        private const val CREATE_SUSPENDED = 0x00000004
        private const val CREATE_UNICODE_ENVIRONMENT = 0x00000400
        private const val CREATE_BREAKAWAY_FROM_JOB = 0x01000000
        private const val EXTENDED_STARTUPINFO_PRESENT = 0x00080000
        private const val STARTF_USESTDHANDLES = 0x00000100
        private const val HANDLE_FLAG_INHERIT = 0x00000001
        private const val PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x00020002L
        private const val ERROR_INSUFFICIENT_BUFFER = 122

        fun start(command: List<String>, environment: Map<String, String>, maxDescendants: Int): WindowsNativeProcess {
            val api = Kernel32Job.INSTANCE
            val security = WinBase.SECURITY_ATTRIBUTES().also {
                it.dwLength = WinDef.DWORD(it.size().toLong())
                it.bInheritHandle = true
                it.write()
            }
            val stdoutReadRef = WinNT.HANDLEByReference()
            val stdoutWriteRef = WinNT.HANDLEByReference()
            if (!api.CreatePipe(stdoutReadRef, stdoutWriteRef, security, 0)) {
                throw IllegalStateException("CreatePipe(stdout) failed with Win32 error ${Native.getLastError()}")
            }
            val openHandles = mutableSetOf(stdoutReadRef.value, stdoutWriteRef.value)
            fun closeTracked(handle: WinNT.HANDLE?) {
                if (handle != null && openHandles.remove(handle)) Kernel32.INSTANCE.CloseHandle(handle)
            }
            var job: WindowsProcessJob? = null
            var attributeList: Pointer? = null
            val information = WinBase.PROCESS_INFORMATION()
            try {
                val stdinReadRef = WinNT.HANDLEByReference()
                val stdinWriteRef = WinNT.HANDLEByReference()
                if (!api.CreatePipe(stdinReadRef, stdinWriteRef, security, 0)) {
                    throw IllegalStateException("CreatePipe(stdin) failed with Win32 error ${Native.getLastError()}")
                }
                val stdoutRead = stdoutReadRef.value
                val stdoutWrite = stdoutWriteRef.value
                val stdinRead = stdinReadRef.value
                val stdinWrite = stdinWriteRef.value
                openHandles += stdinRead
                openHandles += stdinWrite
                if (!api.SetHandleInformation(stdoutRead, HANDLE_FLAG_INHERIT, 0) ||
                    !api.SetHandleInformation(stdinWrite, HANDLE_FLAG_INHERIT, 0)
                ) {
                    throw IllegalStateException("SetHandleInformation failed with Win32 error ${Native.getLastError()}")
                }
                val attributeSize = SizeTByReference()
                val sizingSucceeded = api.InitializeProcThreadAttributeList(null, 1, 0, attributeSize)
                if (sizingSucceeded || Native.getLastError() != ERROR_INSUFFICIENT_BUFFER) {
                    throw IllegalStateException(
                        "InitializeProcThreadAttributeList(size) failed with Win32 error ${Native.getLastError()}",
                    )
                }
                val initializedAttributeList = Memory(attributeSize.value())
                attributeList = initializedAttributeList
                if (!api.InitializeProcThreadAttributeList(initializedAttributeList, 1, 0, attributeSize)) {
                    throw IllegalStateException(
                        "InitializeProcThreadAttributeList failed with Win32 error ${Native.getLastError()}",
                    )
                }
                val inheritedHandles = Memory(2L * Native.POINTER_SIZE)
                inheritedHandles.setPointer(0, stdinRead.pointer)
                inheritedHandles.setPointer(Native.POINTER_SIZE.toLong(), stdoutWrite.pointer)
                if (!api.UpdateProcThreadAttribute(
                        initializedAttributeList,
                        0,
                        Pointer.createConstant(PROC_THREAD_ATTRIBUTE_HANDLE_LIST),
                        inheritedHandles,
                        BaseTSD.SIZE_T(2L * Native.POINTER_SIZE),
                        null,
                        null,
                    )
                ) {
                    throw IllegalStateException(
                        "UpdateProcThreadAttribute(handle list) failed with Win32 error ${Native.getLastError()}",
                    )
                }
                val startup = StartupInfoEx().also {
                    it.StartupInfo.cb = WinDef.DWORD(it.size().toLong())
                    it.StartupInfo.dwFlags = STARTF_USESTDHANDLES
                    it.StartupInfo.hStdInput = stdinRead
                    it.StartupInfo.hStdOutput = stdoutWrite
                    it.StartupInfo.hStdError = stdoutWrite
                    it.lpAttributeList = initializedAttributeList
                    it.write()
                }
                val executable = resolveWindowsExecutable(command.first())
                val launchCommand = listOf(executable.toString()) + command.drop(1)
                val commandMemory = wideMemory(launchCommand.joinToString(" ", transform = ::quoteWindowsArgument))
                val environmentMemory = environmentMemory(environment)
                if (!api.CreateProcessW(
                        WString(executable.toString()),
                        commandMemory,
                        null,
                        null,
                        true,
                        CREATE_SUSPENDED or CREATE_UNICODE_ENVIRONMENT or CREATE_BREAKAWAY_FROM_JOB or
                            EXTENDED_STARTUPINFO_PRESENT,
                        environmentMemory,
                        null,
                        startup.pointer,
                        information,
                    )
                ) {
                    throw IllegalStateException("CreateProcessW failed with Win32 error ${Native.getLastError()}")
                }
                api.DeleteProcThreadAttributeList(initializedAttributeList)
                attributeList = null
                openHandles += information.hProcess
                openHandles += information.hThread
                closeTracked(stdoutWrite)
                closeTracked(stdinRead)
                closeTracked(stdinWrite)
                job = WindowsProcessJob.create(maxDescendants)
                job.assign(information.hProcess)
                if (api.ResumeThread(information.hThread) == -1) {
                    throw IllegalStateException("ResumeThread failed with Win32 error ${Native.getLastError()}")
                }
                closeTracked(information.hThread)
                openHandles.remove(information.hProcess)
                openHandles.remove(stdoutRead)
                return WindowsNativeProcess(
                    information.hProcess,
                    information.dwProcessId.toLong(),
                    stdoutRead,
                    job,
                )
            } catch (error: Throwable) {
                attributeList?.let(api::DeleteProcThreadAttributeList)
                runCatching { job?.close() }
                information.hProcess?.takeIf(openHandles::contains)?.let { runCatching { api.TerminateProcess(it, 1) } }
                openHandles.toList().forEach(::closeTracked)
                throw error
            }
        }

        private fun environmentMemory(environment: Map<String, String>): Memory {
            val block = environment.entries
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
                .joinToString("\u0000", postfix = "\u0000\u0000") { "${it.key}=${it.value}" }
            val memory = Memory(block.length.toLong() * Native.WCHAR_SIZE)
            block.forEachIndexed { index, character -> memory.setChar(index.toLong() * Native.WCHAR_SIZE, character) }
            return memory
        }

        private fun wideMemory(value: String): Memory =
            Memory((value.length.toLong() + 1) * Native.WCHAR_SIZE).also { it.setWideString(0, value) }

        private fun quoteWindowsArgument(argument: String): String {
            if (argument.isEmpty()) return "\"\""
            val quoted = StringBuilder("\"")
            var backslashes = 0
            for (character in argument) {
                if (character == '\\') {
                    backslashes++
                } else {
                    if (character == '\"') quoted.append("\\".repeat(backslashes * 2 + 1))
                    else quoted.append("\\".repeat(backslashes))
                    quoted.append(character)
                    backslashes = 0
                }
            }
            quoted.append("\\".repeat(backslashes * 2)).append('\"')
            return quoted.toString()
        }
    }
}

internal class WindowsHandleInputStream(private val handle: WinNT.HANDLE) : InputStream() {
    private val closed = AtomicBoolean()

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed.get()) return -1
        if (length == 0) return 0
        val target = if (offset == 0 && length == buffer.size) buffer else ByteArray(length)
        val read = IntByReference()
        if (!Kernel32Job.INSTANCE.ReadFile(handle, target, length, read, null)) {
            val error = Native.getLastError()
            if (error == ERROR_BROKEN_PIPE) return -1
            throw java.io.IOException("ReadFile failed with Win32 error $error")
        }
        if (read.value == 0) return -1
        if (target !== buffer) target.copyInto(buffer, offset, 0, read.value)
        return read.value
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) Kernel32.INSTANCE.CloseHandle(handle)
    }
}

private const val WAIT_OBJECT_0 = 0
private const val WAIT_TIMEOUT = 258
private const val INFINITE = -1
private const val STILL_ACTIVE = 259
private const val ERROR_BROKEN_PIPE = 109

internal interface Kernel32Job : StdCallLibrary {
    fun CreateJobObjectW(jobAttributes: Pointer?, name: WString?): WinNT.HANDLE?
    fun SetInformationJobObject(job: WinNT.HANDLE, infoClass: Int, info: Pointer, size: Int): Boolean
    fun AssignProcessToJobObject(job: WinNT.HANDLE, process: WinNT.HANDLE): Boolean
    fun CreateIoCompletionPort(
        fileHandle: WinNT.HANDLE,
        existingPort: WinNT.HANDLE?,
        completionKey: BaseTSD.ULONG_PTR,
        concurrentThreads: Int,
    ): WinNT.HANDLE?
    fun GetQueuedCompletionStatus(
        completionPort: WinNT.HANDLE,
        bytesTransferred: IntByReference,
        completionKey: PointerByReference,
        overlapped: PointerByReference,
        milliseconds: Int,
    ): Boolean
    fun CreatePipe(
        readPipe: WinNT.HANDLEByReference,
        writePipe: WinNT.HANDLEByReference,
        attributes: WinBase.SECURITY_ATTRIBUTES,
        size: Int,
    ): Boolean
    fun SetHandleInformation(handle: WinNT.HANDLE, mask: Int, flags: Int): Boolean
    fun CreateProcessW(
        applicationName: WString?,
        commandLine: Pointer,
        processAttributes: Pointer?,
        threadAttributes: Pointer?,
        inheritHandles: Boolean,
        creationFlags: Int,
        environment: Pointer?,
        currentDirectory: WString?,
        startupInfo: Pointer,
        processInformation: WinBase.PROCESS_INFORMATION,
    ): Boolean
    fun InitializeProcThreadAttributeList(
        attributeList: Pointer?,
        attributeCount: Int,
        flags: Int,
        size: SizeTByReference,
    ): Boolean
    fun UpdateProcThreadAttribute(
        attributeList: Pointer,
        flags: Int,
        attribute: Pointer,
        value: Pointer,
        size: BaseTSD.SIZE_T,
        previousValue: Pointer?,
        returnSize: Pointer?,
    ): Boolean
    fun DeleteProcThreadAttributeList(attributeList: Pointer)
    fun ResumeThread(thread: WinNT.HANDLE): Int
    fun ReadFile(handle: WinNT.HANDLE, buffer: ByteArray, count: Int, read: IntByReference, overlapped: Pointer?): Boolean
    fun WaitForSingleObject(handle: WinNT.HANDLE, milliseconds: Int): Int
    fun GetExitCodeProcess(handle: WinNT.HANDLE, exitCode: IntByReference): Boolean
    fun TerminateProcess(handle: WinNT.HANDLE, exitCode: Int): Boolean

    companion object {
        val INSTANCE: Kernel32Job = Native.load(
            "kernel32",
            Kernel32Job::class.java,
            W32APIOptions.DEFAULT_OPTIONS,
        )
    }
}

internal class SizeTByReference : ByReference(Native.POINTER_SIZE) {
    init {
        if (Native.POINTER_SIZE == 8) pointer.setLong(0, 0) else pointer.setInt(0, 0)
    }

    fun value(): Long = if (Native.POINTER_SIZE == 8) {
        pointer.getLong(0)
    } else {
        Integer.toUnsignedLong(pointer.getInt(0))
    }
}

@Structure.FieldOrder("StartupInfo", "lpAttributeList")
internal class StartupInfoEx : Structure() {
    @JvmField var StartupInfo = WinBase.STARTUPINFO()
    @JvmField var lpAttributeList: Pointer? = null
}

@Structure.FieldOrder("CompletionKey", "CompletionPort")
internal class JobObjectAssociateCompletionPort : Structure() {
    @JvmField var CompletionKey: Pointer? = null
    @JvmField var CompletionPort: WinNT.HANDLE? = null
}

@Structure.FieldOrder(
    "PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags",
    "MinimumWorkingSetSize", "MaximumWorkingSetSize", "ActiveProcessLimit",
    "Affinity", "PriorityClass", "SchedulingClass",
)
internal class JobObjectBasicLimitInformation : Structure() {
    @JvmField var PerProcessUserTimeLimit = 0L
    @JvmField var PerJobUserTimeLimit = 0L
    @JvmField var LimitFlags = 0
    @JvmField var MinimumWorkingSetSize = BaseTSD.SIZE_T()
    @JvmField var MaximumWorkingSetSize = BaseTSD.SIZE_T()
    @JvmField var ActiveProcessLimit = WinDef.DWORD()
    @JvmField var Affinity = BaseTSD.ULONG_PTR()
    @JvmField var PriorityClass = 0
    @JvmField var SchedulingClass = 0
}

@Structure.FieldOrder(
    "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
    "ReadTransferCount", "WriteTransferCount", "OtherTransferCount",
)
internal class IoCounters : Structure() {
    @JvmField var ReadOperationCount = 0L
    @JvmField var WriteOperationCount = 0L
    @JvmField var OtherOperationCount = 0L
    @JvmField var ReadTransferCount = 0L
    @JvmField var WriteTransferCount = 0L
    @JvmField var OtherTransferCount = 0L
}

@Structure.FieldOrder(
    "BasicLimitInformation", "IoInfo", "ProcessMemoryLimit", "JobMemoryLimit",
    "PeakProcessMemoryUsed", "PeakJobMemoryUsed",
)
internal class JobObjectExtendedLimitInformation : Structure() {
    @JvmField var BasicLimitInformation = JobObjectBasicLimitInformation()
    @JvmField var IoInfo = IoCounters()
    @JvmField var ProcessMemoryLimit = BaseTSD.SIZE_T()
    @JvmField var JobMemoryLimit = BaseTSD.SIZE_T()
    @JvmField var PeakProcessMemoryUsed = BaseTSD.SIZE_T()
    @JvmField var PeakJobMemoryUsed = BaseTSD.SIZE_T()
}
