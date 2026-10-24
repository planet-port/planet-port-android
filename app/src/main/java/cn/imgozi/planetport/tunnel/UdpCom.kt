package cn.imgozi.planetport.tunnel

import android.net.VpnService
import com.zerotier.sdk.PacketSender
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

/**
 * UDP 9994 物理收发（对齐 iOS 手写 socket 路径；Android 不 bind 出口源 IP——
 * protect 后内核按物理路由选源，iOS 的源 IP bind 是 extension 环境特例）。
 *
 * socket 必须 protect：establish 后默认路由指向自家 TUN，不 protect 的
 * socket 发出的 ZT 物理包会被抓回 TUN，形成环回黑洞。
 */
internal class UdpCom(
    private val vpnService: VpnService,
    private val onPacket: (data: ByteArray, remote: InetSocketAddress) -> Unit,
) {
    @Volatile private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    @Volatile private var generation = 0

    fun start() {
        shutdown()
        val gen = ++generation
        val s = DatagramSocket(null as java.net.SocketAddress?)
        s.reuseAddress = true
        s.bind(InetSocketAddress(9994))
        vpnService.protect(s)
        socket = s
        val t = Thread {
            val buf = ByteArray(65536)
            while (generation == gen) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    s.receive(pkt)
                } catch (e: SocketException) {
                    break // shutdown 关闭 socket 触发
                } catch (e: IOException) {
                    continue
                }
                if (pkt.length <= 0) continue
                val remote = InetSocketAddress(pkt.address, pkt.port)
                // 拷贝后投递，避免 buf 复用竞争（对齐 iOS udpReceiveLoop）
                onPacket(buf.copyOf(pkt.length), remote)
            }
        }
        t.name = "zt-udp"
        t.start()
        thread = t
    }

    /** 发物理包（PacketSender 回调，ZT 原生线程调用）；返回 0 成功（sendto 语义） */
    private fun send(remote: InetSocketAddress, data: ByteArray): Int {
        val s = socket ?: return -1
        val host: InetAddress = remote.address ?: return -1
        return try {
            s.send(DatagramPacket(data, data.size, host, remote.port))
            0
        } catch (e: IOException) {
            -1
        }
    }

    fun shutdown() {
        generation += 1
        socket?.close() // 使阻塞的 receive 抛 SocketException 退出
        socket = null
        // join 收包线程：close 后线程可能仍在 onPacket → processWirePacket JNI 内，
        // 直接放行到 node.close() 会 native UAF；join(500) 上限阻塞，停机路径可接受
        thread?.join(500)
        thread = null
    }

    /** 桥接为 SDK PacketSender（Node init 的 sender 参数） */
    fun asPacketSender(): PacketSender = PacketSender { _, remoteAddr, packetData, _ ->
        if (remoteAddr == null || packetData == null) -1 else send(remoteAddr, packetData)
    }
}
