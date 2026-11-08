package me.rerere.rikkahub.data.stellar

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import roro.stellar.Stellar
import roro.stellar.StellarHelper
import roro.stellar.StellarRemoteProcess
import java.util.concurrent.TimeUnit

private const val TAG = "StellarManager"

/** Stellar 的权限名, 与 AndroidManifest 里 `roro.stellar.permissions` 的 meta-data 对应。 */
private const val PERMISSION = "stellar"

/** 本应用只会发起一种 Stellar 授权请求, 请求码随意固定即可。 */
private const val PERMISSION_REQUEST_CODE = 8730

/**
 * 在调用方给的命令超时之外, 再留一段给 binder 往返本身的余量。
 * 万一服务端没按自己的超时收尾(进程被 OOM 杀、binder 死亡等), 也不至于把 [exec] 挂死。
 */
private const val CALL_TIMEOUT_SLACK_MS = 5_000L

/** 回给模型的上限, 与 agent 里 shizuku_exec 的口径一致(8000/2000), 免得一条命令吃光上下文。 */
private const val MAX_STDOUT_CHARS = 8_000
private const val MAX_STDERR_CHARS = 2_000

/** 进程结束后等收集线程收尾的时间。 */
private const val COLLECTOR_JOIN_MS = 1_500L

/**
 * `roro.stellar` SDK 的薄封装: 状态查询 + 权限 + 监听器 + 特权命令执行。
 *
 * 与 rikkahub-agent 里那套 Shizuku 实现的关键差别: Stellar 的
 * [Stellar.newProcess] 是 public 的(Shizuku 13.1.5 里是 private, 所以 agent 只能绕道
 * `bindUserService` 起一个自定义 AIDL 用户服务来执行命令)。这里直接 newProcess 拿到
 * [roro.stellar.StellarRemoteProcess], 它本身就是 `java.lang.Process` 子类, 于是可以
 * 直接复用 :workspace 模块里现成的 [readResult] —— 有界捕获、超时 destroyForcibly、
 * 部分输出保留都已经是官方实现, 不需要再写一份。
 *
 * 无状态 object, 直接以 [Context] 入参被工具和设置页调用, 与
 * [me.rerere.workspace] 里的 HostShellRunner 风格一致, 不需要 DI。
 */
object StellarManager {

    // --- 状态 -------------------------------------------------------------------------------

    fun isInstalled(context: Context): Boolean =
        runCatching { StellarHelper.isManagerInstalled(context) }.getOrDefault(false)

    fun isBinderAlive(): Boolean = runCatching { Stellar.pingBinder() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean =
        runCatching { Stellar.checkSelfPermission(PERMISSION) }.getOrDefault(false)

    fun status(context: Context): StellarStatus = StellarStatusMapper.compute(
        installed = isInstalled(context),
        binderAlive = isBinderAlive(),
        permissionGranted = isPermissionGranted(),
    )

    /** 服务进程的 UID: 0 = root, 2000 = adb/shell; 服务未运行时为 null。 */
    fun serviceUid(): Int? = runCatching { Stellar.uid }.getOrNull()

    /** 服务端 API 版本; 服务未运行时为 null。 */
    fun serviceVersion(): Int? = runCatching { Stellar.version }.getOrNull()

    /**
     * 发起 Stellar 的授权流程。binder 没活时直接 no-op(没有对象可以问)。
     *
     * 结果不会从 Activity 回调回来, 而是通过注册的
     * [Stellar.OnRequestPermissionResultListener] 异步送达: 调用方必须先注册监听器
     * (见 [addRequestPermissionResultListener])。
     *
     * 只在两个地方被调用: 设置页里用户显式点击"权限"一行, 以及助手本地工具页打开
     * `stellar_exec` 开关时 —— 绝不在应用启动或首次对话时自动弹窗。
     */
    fun requestPermission() {
        if (!isBinderAlive()) return
        runCatching { Stellar.requestPermission(PERMISSION, PERMISSION_REQUEST_CODE) }
            .onFailure { Log.w(TAG, "requestPermission failed", it) }
    }

    /** 打开 Stellar 管理器; 没装则返回 false, 由调用方决定是否跳下载页。 */
    fun openManager(context: Context): Boolean =
        runCatching { StellarHelper.openManager(context) }.getOrDefault(false)

    // --- 监听器(设置页用 DisposableEffect 成对注册/注销) ------------------------------------

    fun addBinderReceivedListener(listener: Stellar.OnBinderReceivedListener) {
        runCatching { Stellar.addBinderReceivedListenerSticky(listener) }
    }

    fun removeBinderReceivedListener(listener: Stellar.OnBinderReceivedListener) {
        runCatching { Stellar.removeBinderReceivedListener(listener) }
    }

    fun addBinderDeadListener(listener: Stellar.OnBinderDeadListener) {
        runCatching { Stellar.addBinderDeadListener(listener) }
    }

    fun removeBinderDeadListener(listener: Stellar.OnBinderDeadListener) {
        runCatching { Stellar.removeBinderDeadListener(listener) }
    }

    fun addRequestPermissionResultListener(listener: Stellar.OnRequestPermissionResultListener) {
        runCatching { Stellar.addRequestPermissionResultListener(listener) }
    }

    fun removeRequestPermissionResultListener(listener: Stellar.OnRequestPermissionResultListener) {
        runCatching { Stellar.removeRequestPermissionResultListener(listener) }
    }

    // --- stellar_exec: 直接起特权进程 ------------------------------------------------------

    /**
     * 以 Stellar 服务身份(shell UID, 等同 `adb shell`, 不是 root)执行 [command]。
     *
     * 服务没准备好、进程起不来、或调用超时, 都返回结构化错误对象而不是抛异常,
     * 调用方可以直接把它当作工具结果回给模型。
     *
     * 注意这里**没有**复用 :workspace 的 me.rerere.workspace.readResult: 它内部用
     * `Process.waitFor(timeout, TimeUnit)` 判断超时, 而 JDK 那个默认实现是轮询
     * `exitValue()` 并靠捕获 `IllegalThreadStateException` 来判定"还没退出"。
     * Stellar 服务端的 `Process.exitValue()` 抛的确实是 `IllegalThreadStateException`
     * ("process hasn't exited"), 但经 AIDL 代理跨 binder 回来后会被重新包成
     * `IllegalArgumentException`(见 `IRemoteProcess$Stub$Proxy.exitValue`), 于是异常直接
     * 穿透, 命令必定失败。SDK 为此专门提供了 [StellarRemoteProcess.waitForTimeout],
     * 由服务端轮询真实进程, 语义正确, 这里改用它。
     */
    suspend fun exec(context: Context, command: String, timeoutMs: Int): JsonObject {
        StellarStatusMapper.errorFor(status(context))?.let { return it }

        // 硬性拦截: 与审批无关, 即使开了"自动审批"或用户点了同意也不放行
        StellarCommandGuard.rejection(command)?.let { blocked ->
            return buildJsonObject {
                put("error", "command_blocked")
                put("rule", blocked.rule)
                put("detail", blocked.detail)
                put(
                    "recovery",
                    "Blocked by a hard safety rule; approval cannot override it. If this is really " +
                        "needed, ask the user to run it manually in a terminal."
                )
            }
        }

        val process = runCatching {
            Stellar.newProcess(arrayOf("sh", "-c", command), null, null)
        }.getOrElse { t ->
            Log.e(TAG, "newProcess threw", t)
            return buildJsonObject {
                put("error", "stellar_spawn_failed")
                put("reason", "${t::class.java.simpleName}: ${t.message}")
                put(
                    "recovery",
                    "Could not spawn a process in the Stellar service. The service may have just " +
                        "restarted or the permission was revoked. Retry; if it keeps failing, check " +
                        "Settings -> Stellar and restart the Stellar service."
                )
            }
        }

        val stdout = BoundedTextCollector(process.inputStream, MAX_STDOUT_CHARS)
        val stderr = BoundedTextCollector(process.errorStream, MAX_STDERR_CHARS)
        // 立即给 stdin 一个 EOF: 否则 `cat`、`read` 这类等输入的命令会一直挂到超时
        runCatching { process.outputStream.close() }

        val exited = withTimeoutOrNull(timeoutMs + CALL_TIMEOUT_SLACK_MS) {
            runInterruptible(Dispatchers.IO) { awaitExit(process, timeoutMs) }
        }
        if (exited == null) {
            // 外层兜底超时(服务端 waitForTimeout 没返回): 直接杀掉, 拿已捕获的部分输出
            runCatching { process.destroy() }
            stdout.join(COLLECTOR_JOIN_MS)
            stderr.join(COLLECTOR_JOIN_MS)
            return buildJsonObject {
                put("error", "stellar_call_timeout")
                put(
                    "recovery",
                    "The Stellar service did not respond within ${(timeoutMs + CALL_TIMEOUT_SLACK_MS) / 1000}s; " +
                        "the process was killed. Retry, or raise timeout_ms."
                )
                put("partial_stdout", stdout.text())
                put("partial_stderr", stderr.text())
            }
        }

        if (!exited) {
            runCatching { process.destroy() }
        }
        stdout.join(COLLECTOR_JOIN_MS)
        stderr.join(COLLECTOR_JOIN_MS)
        val out = stdout.text()
        val err = stderr.text()
        val truncated = stdout.truncated || stderr.truncated

        if (!exited) {
            return buildJsonObject {
                put("error", "command_timeout")
                put(
                    "recovery",
                    "Command did not finish within ${timeoutMs / 1000}s; the process was killed. " +
                        "Raise timeout_ms if it genuinely needs longer. Partial output captured " +
                        "before the timeout is included."
                )
                put("partial_stdout", out)
                put("partial_stderr", err)
            }
        }

        // 只有确认已退出才取退出码; 万一远端状态不一致, 退化成 -1 而不是抛异常
        val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
        return buildJsonObject {
            put("success", exitCode == 0)
            put("exit_code", exitCode)
            put("stdout", out)
            put("stderr", err)
            if (truncated) put("truncated", true)
            // 0 = root, 2000 = adb/shell: 决定哪些命令能跑, 值得每次都带上
            serviceUid()?.let { put("uid", it) }
        }
    }

    /**
     * 等进程退出。优先用 SDK 的 [StellarRemoteProcess.waitForTimeout](服务端轮询真实进程);
     * 服务端版本不支持时退化成客户端轮询 [StellarRemoteProcess.alive]。
     *
     * @return true = 在 [timeoutMs] 内退出了
     */
    private fun awaitExit(process: StellarRemoteProcess, timeoutMs: Int): Boolean {
        val viaSdk = runCatching { process.waitForTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS) }
        if (viaSdk.isSuccess) return viaSdk.getOrThrow()

        Log.w(TAG, "waitForTimeout unsupported, falling back to alive() polling", viaSdk.exceptionOrNull())
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            // alive() 在 binder 已死时抛 IllegalStateException, 视作进程已不在
            val running = runCatching { process.alive() }.getOrDefault(false)
            if (!running) return true
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }
}
