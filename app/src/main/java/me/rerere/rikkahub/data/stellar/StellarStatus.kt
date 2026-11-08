package me.rerere.rikkahub.data.stellar

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Stellar 集成的四种状态。 */
enum class StellarStatus {
    /** 没装 Stellar 管理器, 也没有活着的 binder。 */
    NOT_INSTALLED,

    /** 装了管理器, 但特权服务没跑(没有 binder)。 */
    NOT_RUNNING,

    /** 服务在跑, 但用户还没给 RikkaHub 授权。 */
    PERMISSION_DENIED,

    /** 服务在跑且已授权, `stellar_exec` 可以用了。 */
    READY,
}

/**
 * 状态 -> 结构化错误 的纯函数映射。刻意不依赖 [roro.stellar.Stellar] 这个静态单例
 * (它任何一次查询都要求 binder 活着), 这样映射本身可以在没有真机的情况下单测。
 */
object StellarStatusMapper {

    fun compute(
        installed: Boolean,
        binderAlive: Boolean,
        permissionGranted: Boolean,
    ): StellarStatus = when {
        // binder 优先: pingBinder() 通就证明服务真的在跑, 此时绝不能报"未安装"。
        // 包名查不到的原因可能只是 Android 11+ 的包可见性限制(见 AndroidManifest 的 <queries>),
        // 或者服务是通过 ADB / Root 从别的途径拉起来的。
        !binderAlive && !installed -> StellarStatus.NOT_INSTALLED
        !binderAlive -> StellarStatus.NOT_RUNNING
        !permissionGranted -> StellarStatus.PERMISSION_DENIED
        else -> StellarStatus.READY
    }

    /** 非 READY 状态对应的工具结果错误对象; READY 返回 null。 */
    fun errorFor(status: StellarStatus): JsonObject? = when (status) {
        StellarStatus.READY -> null

        StellarStatus.NOT_INSTALLED -> buildJsonObject {
            put("error", "stellar_not_installed")
            put(
                "recovery",
                "Stellar manager is not installed. Install Stellar " +
                    "(https://github.com/roro2239/Stellar/releases), start its service (ADB or root), " +
                    "then grant RikkaHub access in Settings -> Stellar."
            )
        }

        StellarStatus.NOT_RUNNING -> buildJsonObject {
            put("error", "stellar_not_running")
            put(
                "recovery",
                "Stellar manager is installed but its privileged service is not running. Open the " +
                    "Stellar manager and start the service (ADB mode may need re-pairing wireless " +
                    "debugging), then retry."
            )
        }

        StellarStatus.PERMISSION_DENIED -> buildJsonObject {
            put("error", "stellar_permission_denied")
            put(
                "recovery",
                "RikkaHub has no Stellar permission. Open Settings -> Stellar and tap the " +
                    "permission row to grant it, then retry."
            )
        }
    }
}
