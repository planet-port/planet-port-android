/*
 * ZT_jniguard.h - 本仓库自维护文件（无上游对应）
 *
 * 用途：
 *   ZeroTier 核心在自有原生线程上回调 JNI 绑定层（事件、状态存取、
 *   帧与包收发等），这些线程默认未挂接（attach）到 JVM。官方原版
 *   ZT_jniutils.h 的 GETENV 只做 GetEnv + assert，遇到未挂接线程时
 *   release 构建（NDEBUG）下 env 无效，实测在 ICMPv6 Neighbor
 *   Solicitation 处理路径上直接 native 崩溃。
 *
 *   本守卫在作用域入口按需 AttachCurrentThread、作用域结束时
 *   DetachCurrentThread；线程本已挂接（如从 Java 进入的 JNI 调用）
 *   则不会 Detach。模式参照官方 java/jni/com_zerotierone_sdk_Node.cpp
 *   的 ScopedJNIThreadAttacher（MPL-2.0）。
 *
 *   ZT_jniutils.h 的 GETENV 宏展开为本守卫的局部对象，宏签名
 *   GETENV(env, vm) 保持不变，全部调用点无需改动。
 */

#ifndef ZT_jniguard_h_
#define ZT_jniguard_h_

#include <jni.h>

//
// RAII 守卫：构造时 GetEnv 取当前线程 JNIEnv，线程未挂接 JVM 时先
// Attach 并记住需要卸载，析构只对这种情况 Detach。取 env 失败时
// status() 非 JNI_OK、get() 返回 NULL，由 GETENV 宏展开点记日志
// （LOGE 依赖各编译单元的 LOG_TAG，只能在宏展开处使用）。
//
class ScopedJniEnv {
public:
    explicit ScopedJniEnv(JavaVM *vm)
        : vm_(vm), env_(NULL), detachOnExit_(false), status_(JNI_ERR) {
        if (vm_ == NULL) {
            return;
        }

        status_ = vm_->GetEnv(reinterpret_cast<void **>(&env_), JNI_VERSION_1_6);
        if (status_ == JNI_OK) {
            return;
        }
        if (status_ != JNI_EDETACHED) {
            env_ = NULL;
            return;
        }

        //
        // 核心原生线程默认未挂接 JVM：这里挂上，作用域结束再卸下。
        // 从 Java 进入的本已挂接线程不走此分支，也就不会被 Detach。
        //
        status_ = vm_->AttachCurrentThread(&env_, NULL);
        detachOnExit_ = (status_ == JNI_OK);
        if (!detachOnExit_) {
            env_ = NULL;
        }
    }

    ~ScopedJniEnv() {
        if (detachOnExit_) {
            vm_->DetachCurrentThread();
        }
    }

    JNIEnv *get() const { return env_; }

    jint status() const { return status_; }

private:
    JavaVM *vm_;
    JNIEnv *env_;
    bool detachOnExit_;
    jint status_;

    ScopedJniEnv(const ScopedJniEnv &) = delete;
    ScopedJniEnv &operator=(const ScopedJniEnv &) = delete;
};

#endif // ZT_jniguard_h_
