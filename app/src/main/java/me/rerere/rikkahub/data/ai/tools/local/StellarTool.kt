package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.stellar.StellarManager

private const val DEFAULT_TIMEOUT_MS = 30_000
private const val MIN_TIMEOUT_MS = 1_000

/** 与工作区 shell 的上限对齐(300s / 5min)。 */
private const val MAX_TIMEOUT_MS = 300_000

/**
 * 用 Stellar 服务执行 shell 命令。
 *
 * 前置条件: 装了 Stellar 管理器、服务在跑、并且用户在 设置 -> Stellar 里授权过。
 * 权限不会由这个工具去请求, 也不会自动弹窗, 只由设置页 / 工具开关里的显式点击触发。
 *
 * 描述刻意保持**静态**(不按当前 uid 动态拼字符串): 工具描述是提示词前缀的一部分,
 * 每次调用都变会让前缀缓存失效, 得不偿失。当前身份由每次返回里的 `uid` 字段体现,
 * 模型也可以直接跑 `id` 自己确认。
 *
 * 审批由 设置 -> Stellar 的"自动审批"开关控制(默认关); 但
 * [me.rerere.rikkahub.data.stellar.StellarCommandGuard] 的硬性拦截不受开关影响。
 */
fun buildStellarExecTool(context: Context, settingsStore: SettingsStore): Tool = Tool(
    name = "stellar_exec",
    description = """
        Run a shell command with Stellar privileges. Privilege level follows how Stellar was
        started (ADB = adb shell, root = root); results carry the uid. Destructive commands are
        always blocked.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run, e.g. 'pm list packages -3'")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in milliseconds. Default $DEFAULT_TIMEOUT_MS, max $MAX_TIMEOUT_MS.")
                })
            },
            required = listOf("command")
        )
    },
    // 设置页里的"自动审批"打开后就不再逐次确认; 每次调用都读一次当前值, 改了立刻生效
    needsApproval = { !settingsStore.settingsFlow.value.stellarAutoApprove },
    execute = { input ->
        val command = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        if (command.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "command is required") }.toString()
                )
            )
        }
        val timeoutMs = (input.jsonObject["timeout_ms"]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val result = StellarManager.exec(context, command, timeoutMs)
        listOf(UIMessagePart.Text(result.toString()))
    }
)
