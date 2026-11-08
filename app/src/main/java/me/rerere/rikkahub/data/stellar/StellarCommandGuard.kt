package me.rerere.rikkahub.data.stellar

/**
 * `stellar_exec` 的硬性拦截线。
 *
 * 与"审批"是两回事: 审批拦的是模型误判, 而这里拦的是**即使你点了同意也不该跑**的命令。
 * Stellar 服务如果用 Root 方式启动(uid=0), 下面这些命令一条就够把手机变成砖, 所以
 * 无论 [me.rerere.rikkahub.data.datastore.Settings.stellarAutoApprove] 开没开, 都直接拒绝。
 *
 * 刻意保守: 只拦高置信度的"核弹"命令, 不做通用危险命令检测——否则 `rm -rf /data/local/tmp/x`
 * 这种正常操作会被误伤, 用户很快就会把这个功能当成噪音。
 *
 * 纯函数, 不依赖 Android, 可以在 host JVM 上单测。
 */
object StellarCommandGuard {

    /** 被拦下的命令: [rule] 是命中的规则名(便于排查), [detail] 是给人看的具体原因。 */
    data class Blocked(val rule: String, val detail: String)

    /** 递归删除会毁掉整个系统的根目录集合(规范化之后比较)。 */
    private val DANGEROUS_ROOTS = setOf(
        "/", "/system", "/system_root", "/vendor", "/product", "/odm", "/oem",
        "/data", "/boot", "/etc", "/dev", "/proc", "/sys",
        "/storage", "/sdcard", "/mnt", "/sbin", "~",
    )

    /** 分区/文件系统层面的破坏工具, 无论参数是什么都拦。 */
    private val FORMATTERS = setOf(
        "mkfs", "mke2fs", "mkfs.ext4", "mkfs.f2fs", "mkfs.ext2", "mkfs.vfat",
        "make_f2fs", "wipefs", "fdisk", "sgdisk", "parted", "flash_erase",
    )

    /** 直接关机/重启类。 */
    private val POWER = setOf("reboot", "poweroff", "halt", "shutdown")

    /** dd 里这些目标设备写下去不会毁系统, 放行。 */
    private val SAFE_DD_TARGETS = setOf("/dev/null", "/dev/zero")

    /**
     * @return 命中拦截规则时返回 [Blocked], 放行时返回 null
     */
    fun rejection(command: String): Blocked? {
        // 写块设备的重定向先单独看一眼原文, 分词会把 `>/dev/block/sda` 拆开
        if (Regex(""">\s*/dev/(block|disk)/""").containsMatchIn(command)) {
            return Blocked("redirect_to_block_device", "Redirecting output to a block device overwrites partition contents")
        }

        for (tokens in segments(command)) {
            val argv0 = tokens.first().substringAfterLast('/')
            val args = tokens.drop(1)

            if (argv0 in FORMATTERS) {
                return Blocked("formatter", "$argv0 rewrites partitions or filesystems")
            }

            if (argv0 in POWER) {
                return Blocked("power", "$argv0 reboots or powers off the device")
            }

            // svc power shutdown / svc power reboot
            if (argv0 == "svc" && args.firstOrNull() == "power" &&
                args.getOrNull(1) in setOf("shutdown", "reboot")
            ) {
                return Blocked("power", "svc power ${args.getOrNull(1)} reboots or powers off the device")
            }

            if (argv0 == "rm") {
                val recursive = args.any { it.isRecursiveFlag() }
                val force = args.any { it.isForceFlag() }
                if (recursive && force) {
                    args.filterNot { it.startsWith("-") }.forEach { target ->
                        val normalized = normalizeTarget(target)
                        if (normalized in DANGEROUS_ROOTS) {
                            return Blocked(
                                "rm_system_root",
                                "rm -rf on $target deletes a critical system or userdata directory"
                            )
                        }
                    }
                }
            }

            if (argv0 == "dd") {
                args.filter { it.startsWith("of=") }.forEach { arg ->
                    val target = arg.removePrefix("of=").trim('\'', '"')
                    if (target.startsWith("/dev/") && target !in SAFE_DD_TARGETS) {
                        return Blocked("dd_to_block_device", "dd writing to $target overwrites the device")
                    }
                }
            }

            // mount -o remount,rw /system 之类: 把系统分区重新挂成可写
            if (argv0 == "mount" && args.any { it.contains("remount") } && args.any { it.contains("rw") }) {
                return Blocked("remount_rw", "Remounting a system partition read-write is an uncontrolled modification")
            }

            // kill -9 1: 干掉 init
            if (argv0 in setOf("kill", "killall", "pkill") &&
                args.any { it == "-9" || it == "-KILL" } && args.any { it == "1" }
            ) {
                return Blocked("kill_init", "Killing init (pid 1) crashes the system immediately")
            }
        }
        return null
    }
}

/** 按 shell 的常见分隔符切成若干条简单命令, 再粗分词。引号不处理: 这里只做启发式拦截。 */
private fun segments(command: String): List<List<String>> =
    command.replace("\n", ";")
        .split(";", "&&", "||", "|")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.split(Regex("\\s+")) }

/** `-r` / `-R` / `-rf` / `--recursive` */
private fun String.isRecursiveFlag(): Boolean =
    (startsWith("-") && !startsWith("--") && (contains('r') || contains('R'))) || this == "--recursive"

/** `-f` / `-rf` / `--force` */
private fun String.isForceFlag(): Boolean =
    (startsWith("-") && !startsWith("--") && contains('f')) || this == "--force"

/** 去掉引号与尾部的斜杠和通配符, 让带引号的根目录、`/` 与 `/` 加星号都归一到 `/`。 */
private fun normalizeTarget(raw: String): String {
    var t = raw.trim().trim('\'', '"')
    if (t.endsWith("/*")) t = t.dropLast(2)
    while (t.length > 1 && t.endsWith("/")) t = t.dropLast(1)
    return t.ifEmpty { "/" }
}
