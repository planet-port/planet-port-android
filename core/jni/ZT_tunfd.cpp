// TUN fd 标志位工具（自有文件，非官方 SDK 代码）。
//
// 部分 OEM（如 MIUI/Android 9）的 VpnService TUN fd 出厂即 O_NONBLOCK，
// Java 层 FileInputStream.read 对其表现为「无数据立即返回 0」，阻塞式读
// 循环语义失效。Os.fcntlInt 在 API 30 才转正，低版本属 dark greylist
// hidden API（targetSdk ≥ P 调用即抛 NoSuchMethodError），故在 JNI 层
// 直接 fcntl 恢复阻塞模式，全版本可用。

#include <errno.h>
#include <fcntl.h>
#include <jni.h>

extern "C" {

// 返回 F_GETFL 结果；失败返回负 errno
JNIEXPORT jint JNICALL
Java_cn_imgozi_planetport_tunnel_TunFdUtils_getFlags(JNIEnv *, jobject, jint fd)
{
    int fl = fcntl(fd, F_GETFL);
    return fl < 0 ? -errno : fl;
}

// 清除 O_NONBLOCK。返回 0=成功转为阻塞，1=本就阻塞，负值=失败 errno
JNIEXPORT jint JNICALL
Java_cn_imgozi_planetport_tunnel_TunFdUtils_setBlocking(JNIEnv *, jobject, jint fd)
{
    int fl = fcntl(fd, F_GETFL);
    if (fl < 0) return -errno;
    if (!(fl & O_NONBLOCK)) return 1;
    return fcntl(fd, F_SETFL, fl & ~O_NONBLOCK) == 0 ? 0 : -errno;
}

} // extern "C"
