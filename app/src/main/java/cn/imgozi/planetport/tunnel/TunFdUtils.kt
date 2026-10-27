package cn.imgozi.planetport.tunnel

/**
 * TUN fd 标志位工具（JNI 实现，见 core/jni/ZT_tunfd.cpp）。
 * Os.fcntlInt 在 API 30 才是公开 API，低版本为 dark greylist
 * hidden API（调用即抛 NoSuchMethodError），故走 JNI。
 */
internal object TunFdUtils {
    init {
        // 与 com.zerotier.sdk.Node 同库（ZeroTierOneJNI），重复加载无害
        System.loadLibrary("ZeroTierOneJNI")
    }

    /** F_GETFL 结果，失败返回负 errno */
    external fun getFlags(fd: Int): Int

    /** 清除 O_NONBLOCK：0=转为阻塞 1=本就阻塞 负值=errno */
    external fun setBlocking(fd: Int): Int
}
