package cn.imgozi.planetport.tunnel

/** 以太网常量（帧本体不经过 Node——SDK 语义为 MAC/ethertype 独立传参） */
internal object Ethernet {
    const val TYPE_IPV4 = 0x0800
    const val TYPE_ARP = 0x0806
    const val TYPE_IPV6 = 0x86DD
    /** 48 位全 1 广播地址（0xffffffffffff；Kotlin Long 字面量溢出，取负补码表示） */
    const val BROADCAST_MAC = -0x1000000000000L
}
