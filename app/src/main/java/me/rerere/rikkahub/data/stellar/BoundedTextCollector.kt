package me.rerere.rikkahub.data.stellar

import java.io.IOException
import java.io.InputStream

/**
 * 有界文本收集器: 用一个 daemon 线程把 [stream] 读到 EOF, 只保留前 [maxChars] 个字符。
 *
 * 超出上限后**仍然继续读**并丢弃, 否则管道写满会把子进程堵死、它就永远退不出去
 * (`cat /dev/zero` 这类命令会直接把超时耗光)。
 *
 * 不用 :workspace 里的 `StreamCollector` 是因为它是 `internal`, 跨模块拿不到;
 * 逻辑与它保持一致, 便于日后合并。
 */
internal class BoundedTextCollector(
    stream: InputStream,
    private val maxChars: Int,
) {
    private val builder = StringBuilder()

    @Volatile
    var truncated: Boolean = false
        private set

    private val thread = Thread({
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4_096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    synchronized(builder) {
                        val remaining = maxChars - builder.length
                        if (remaining > 0) {
                            builder.append(buffer, 0, minOf(read, remaining))
                        }
                        if (read > remaining) {
                            truncated = true
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被强杀或管道被关闭时 read() 会抛异常, 保留已读到的内容即可;
            // 不能让异常逃逸, 否则会走线程默认异常处理
        }
    }, "stellar-stream").apply {
        // daemon: 即使远端 fd 迟迟不 EOF, 也不能拖住 JVM 退出
        isDaemon = true
        start()
    }

    fun join(millis: Long) {
        runCatching { thread.join(millis) }
    }

    fun text(): String = synchronized(builder) { builder.toString() }
}
