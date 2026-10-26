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

    /** 换代：拒绝（已停机）返回 false 且关闭新 fd；成功返回 true。调用方须以
     *  返回值为准决定是否记录「已应用」状态——被拒时 TUN 实际不存在 */
    @Synchronized
    fun swap(fd: ParcelFileDescriptor): Boolean {
        if (stopped) {
            // 停机后迟到的 establish（withContext(IO) 内不可取消）：拒绝并关闭新 fd 防泄漏
            fd.close()
            return false
        }
        generation += 1
        val gen = generation
        writeExecutor?.shutdownNow()
        outputStream?.close()
        currentFd?.close() // 先关旧 fd——阻塞在 read() 的旧读线程随即退出
        // join 旧代读线程：fd 关闭唤醒 read 退出；若正处 onTunPacket 的 JNI 内，
        // 等 JNI 返回落地（终审 Minor：换代窗口残窗）
        readThread?.join(500)
        currentFd = fd
        // 部分 OEM TUN fd 出厂即 O_NONBLOCK，read 空读返回 0 令「n<=0 退出」
        // 的阻塞语义失效（MIUI/Android 9 实测）。恢复阻塞模式；失败则读循环
        // 退避轮询兜底，不让 0 返回杀死读线程。
        val blockResult = try {
            TunFdUtils.setBlocking(fd.fd)
        } catch (e: Throwable) {
            TunnelStateBus.log("tun fd setBlocking 异常: ${e.javaClass.simpleName}")
            -1
        }
        if (blockResult < 0) {
            TunnelStateBus.log("tun fd 恢复阻塞失败 errno=$blockResult，读循环将以退避轮询兜底")
        }
        outputStream = FileOutputStream(fd.fileDescriptor)
        writeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "zt-tun-write-$gen") }
        val input = FileInputStream(fd.fileDescriptor)
        readThread = Thread({
            var polled = false
            val buf = ByteArray(65536)
            while (generation == gen) {
                val n = try {
                    input.read(buf)
                } catch (_: IOException) {
                    break // fd 关闭
                }
                if (n == 0) {
                    // 非阻塞 fd 空读（setBlocking 失败的兜底路径）：退避后重试
                    if (!polled) { polled = true; TunnelStateBus.log("tun fd 仍非阻塞，退避轮询 gen=$gen") }
                    Thread.sleep(1)
                    continue
                }
                if (n < 0) break
                onTunPacket(buf.copyOf(n))
            }
            try { input.close() } catch (_: IOException) {}
        }, "zt-tun-read-$gen").also { it.start() }
        return true
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

    /**
     * 拆 TUN（全部网络失效时）：关 fd 即接口消失、系统路由自动撤销，实例保持
     * 可用——后续网络恢复可继续 swap 重建。不带停机闩锁（teardown ≠ 终局）。
     */
    @Synchronized
    fun teardownTun() {
        generation += 1
        writeExecutor?.shutdownNow()
        writeExecutor = null
        outputStream?.close()
        outputStream = null
        currentFd?.close()
        currentFd = null
        readThread?.join(500)
        readThread = null
    }

    /** 终局停机：置闩锁拒绝一切后续 swap（服务生命周期收口专用，拆 TUN 用 teardownTun） */
    @Synchronized
    fun shutdown() {
        stopped = true
        teardownTun()
    }
}
