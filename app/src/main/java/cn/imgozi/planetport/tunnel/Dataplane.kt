package cn.imgozi.planetport.tunnel

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * TUN fd 读写：每次 Builder.establish 返回新 fd（Android 与 iOS 的
 * packetFlow 常驻不同），swap() 负责换代——旧 fd 关闭使读线程退出，
 * 新读线程与专职写线程随新代号启动。
 */
internal class Dataplane(private val onTunPacket: (ByteArray) -> Unit) {
    private var generation = 0
    private var currentFd: ParcelFileDescriptor? = null
    private var writeExecutor: ExecutorService? = null

    @Synchronized
    fun swap(fd: ParcelFileDescriptor) {
        generation += 1
        val gen = generation
        writeExecutor?.shutdownNow()
        currentFd?.close()
        currentFd = fd
        writeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "zt-tun-write-$gen") }
        val input = FileInputStream(fd.fileDescriptor)
        Thread({
            val buf = ByteArray(65536)
            while (generation == gen) {
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    break // fd 关闭
                }
                if (n <= 0) break
                onTunPacket(buf.copyOf(n))
            }
            try { input.close() } catch (_: IOException) {}
        }, "zt-tun-read-$gen").start()
    }

    /** 上行 IP 包写入 tun（消费协程投递，专职写线程顺序化） */
    fun writePackets(packets: List<ByteArray>) {
        if (packets.isEmpty()) return
        val exec = writeExecutor ?: return
        exec.execute {
            val fd = currentFd ?: return@execute
            val out = FileOutputStream(fd.fileDescriptor)
            try {
                for (p in packets) out.write(p)
            } catch (_: IOException) {
                // fd 换代瞬间的写入失败可接受（下次 establish 已重排）
            }
        }
    }

    @Synchronized
    fun shutdown() {
        generation += 1
        writeExecutor?.shutdownNow()
        writeExecutor = null
        currentFd?.close()
        currentFd = null
    }
}
