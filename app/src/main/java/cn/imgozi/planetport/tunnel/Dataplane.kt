package cn.imgozi.planetport.tunnel

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * TUN fd 读写：每次 Builder.establish 返回新 fd（Android 与 iOS 的
 * packetFlow 常驻不同），swap() 负责换代——旧 fd 关闭使读线程退出，
 * 新读线程与专职写线程随新代号启动。
 */
internal class Dataplane(private val onTunPacket: (ByteArray) -> Unit) {
    private var generation = 0
    /** 停机标志——拒绝停机后迟到的 swap（establish 滞留 fd 泄漏） */
    @Volatile private var stopped = false
    private var currentFd: ParcelFileDescriptor? = null
    /** 每代持有一份写流——按 task 新建会被 GC finalizer 关闭共享 fd 杀死在用 TUN */
    private var outputStream: FileOutputStream? = null
    private var writeExecutor: ExecutorService? = null
    /** 当前代读线程——shutdown 须 join 后才能放行 node.close()：线程可能仍处
     *  onTunPacket → processVirtualNetworkFrame JNI 内，直接关 Node 会 native UAF */
    private var readThread: Thread? = null

    @Synchronized
    fun swap(fd: ParcelFileDescriptor) {
        if (stopped) {
            // 停机后迟到的 establish（withContext(IO) 内不可取消）：拒绝并关闭新 fd 防泄漏
            fd.close()
            return
        }
        generation += 1
        val gen = generation
        // 旧代读循环看到 generation 变化即退出，但可能仍在 onTunPacket 的 JNI 内——
        // 等它落地再关旧资源（终审 Minor：换代窗口残窗）
        readThread?.join(500)
        writeExecutor?.shutdownNow()
        outputStream?.close()
        currentFd?.close()
        currentFd = fd
        outputStream = FileOutputStream(fd.fileDescriptor)
        writeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "zt-tun-write-$gen") }
        val input = FileInputStream(fd.fileDescriptor)
        readThread = Thread({
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
        }, "zt-tun-read-$gen").also { it.start() }
    }

    /** 上行 IP 包写入 tun（消费协程投递，专职写线程顺序化） */
    fun writePackets(packets: List<ByteArray>) {
        if (packets.isEmpty()) return
        val exec = writeExecutor ?: return
        try {
            exec.execute {
                val out = outputStream ?: return@execute
                try {
                    for (p in packets) out.write(p)
                } catch (_: IOException) {
                    // fd 换代瞬间的写入失败可接受（下次 establish 已重排）
                }
            }
        } catch (_: RejectedExecutionException) {
            // 与 shutdown 的 shutdownNow 竞态：停机中丢包符合语义，静默返回
        }
    }

    @Synchronized
    fun shutdown() {
        stopped = true
        generation += 1
        writeExecutor?.shutdownNow()
        writeExecutor = null
        outputStream?.close()
        outputStream = null
        currentFd?.close() // 先关 fd 让读循环退出
        currentFd = null
        // join 读线程：关 fd 后线程可能已进入 onTunPacket → processVirtualNetworkFrame
        // JNI 内，不等待就放行 node.close() 会 native UAF（同 supervisor/udp join 收口）。
        // join 期间持本锁无碍——读线程不回取本锁
        readThread?.join(500)
        readThread = null
    }
}
