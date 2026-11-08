package me.rerere.rikkahub.data.stellar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [StellarCommandGuard] 的规则表。纯函数, 不需要设备也不需要 Stellar 服务。
 *
 * 两边都要测: 该拦的必须拦(漏了是安全问题), 不该拦的必须放行(误伤会让这个功能变噪音)。
 */
class StellarCommandGuardTest {

    private fun assertBlocked(command: String, expectedRule: String) {
        val blocked = StellarCommandGuard.rejection(command)
        assertNotNull("应当被拦截: $command", blocked)
        assertEquals("规则名不符: $command", expectedRule, blocked!!.rule)
    }

    private fun assertAllowed(command: String) {
        assertNull("不应被拦截: $command", StellarCommandGuard.rejection(command))
    }

    // --- 该拦的 ---

    @Test
    fun `rm -rf 根目录被拦`() {
        assertBlocked("rm -rf /", "rm_system_root")
    }

    @Test
    fun `rm -rf 通配根目录被拦`() {
        assertBlocked("rm -rf /*", "rm_system_root")
    }

    @Test
    fun `合并写法的 -fr 也认`() {
        assertBlocked("rm -fr /data", "rm_system_root")
    }

    @Test
    fun `引号包住的根目录绕不过去`() {
        assertBlocked("""rm -rf "/" """.trim(), "rm_system_root")
    }

    @Test
    fun `系统分区同样被拦`() {
        assertBlocked("rm -rf /system", "rm_system_root")
        assertBlocked("rm -rf /vendor/", "rm_system_root")
        assertBlocked("rm -rf /sdcard", "rm_system_root")
    }

    @Test
    fun `格式化工具被拦`() {
        assertBlocked("mkfs.ext4 /dev/block/sda1", "formatter")
        assertBlocked("make_f2fs /dev/block/by-name/userdata", "formatter")
        assertBlocked("wipefs -a /dev/block/sda", "formatter")
    }

    @Test
    fun `dd 写块设备被拦`() {
        assertBlocked("dd if=/dev/zero of=/dev/block/sda bs=1M", "dd_to_block_device")
        assertBlocked("dd if=boot.img of='/dev/block/by-name/boot'", "dd_to_block_device")
    }

    @Test
    fun `重启关机被拦`() {
        assertBlocked("reboot", "power")
        assertBlocked("reboot -p", "power")
        assertBlocked("svc power shutdown", "power")
    }

    @Test
    fun `系统分区重挂可写被拦`() {
        assertBlocked("mount -o remount,rw /system", "remount_rw")
        assertBlocked("mount -o rw,remount /", "remount_rw")
    }

    @Test
    fun `杀 init 被拦`() {
        assertBlocked("kill -9 1", "kill_init")
    }

    @Test
    fun `重定向到块设备被拦`() {
        assertBlocked("echo x > /dev/block/sda", "redirect_to_block_device")
    }

    @Test
    fun `链式命令里的危险段也能识别`() {
        assertBlocked("echo hi && rm -rf /", "rm_system_root")
        assertBlocked("pm list packages; reboot", "power")
    }

    // --- 不该拦的 ---

    @Test
    fun `普通命令放行`() {
        assertAllowed("pm list packages -3")
        assertAllowed("dumpsys meminfo")
        assertAllowed("id")
        assertAllowed("ls -la /sdcard/DCIM")
    }

    @Test
    fun `删子目录放行`() {
        assertAllowed("rm -rf /data/local/tmp/build")
        assertAllowed("rm -rf /sdcard/DCIM/Camera")
        assertAllowed("rm -rf ./build")
    }

    @Test
    fun `不带 -rf 的 rm 放行`() {
        // 单文件删除交给审批, 不硬拦
        assertAllowed("rm /sdcard/test.txt")
        assertAllowed("rm -f /data/local/tmp/x")
    }

    @Test
    fun `dd 写普通文件或 dev null 放行`() {
        assertAllowed("dd if=/dev/zero of=/dev/null bs=1M count=1")
        assertAllowed("dd if=/dev/zero of=/data/local/tmp/1m bs=1M count=1")
    }

    @Test
    fun `只读挂载查询放行`() {
        assertAllowed("mount")
        assertAllowed("mount | grep system")
    }

    @Test
    fun `含关键字的普通文本不误伤`() {
        assertAllowed("grep -r reboot /system/etc/init")
        assertAllowed("cat /proc/sys/kernel/random/boot_id")
    }
}
