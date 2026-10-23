# Android JNI 构建脚本（树外维护，不改动 externals/core 子模块）
#
# 官方 java/CMakeLists.txt 是陈旧的显式源码清单（引用 1.14 起已删除的
# node/C25519.cpp、node/Defaults.cpp 等），不可直接使用，故在子模块外
# 自行维护本文件。核心源码用 GLOB 纳入，未来小幅升级无需改这里。
#
# JNI 绑定层（../core/jni/）来自官方 1.16.2 java/jni，其中
# ZT_jniutils.h 采用 kaaass 版本：JNIEnvGuard 保证核心工作线程回调时
# 自动 Attach/DetachCurrentThread（修复 ICMPv6 NS 触发的 native 崩溃）。

cmake_minimum_required(VERSION 3.13)

project(ZeroTierOneJNI)

# ZeroTier 1.14+ 要求 C++17（官方 java/CMakeLists 直到 1.16.2 之后才补上）
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/core)
set(JNI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../core/jni)

# 1.14+ 删除了 ext/lz4、ext/json-parser，新增 node/ECC.cpp、
# node/PacketMultiplexer.cpp 等，GLOB 自动覆盖。
# CONFIGURE_DEPENDS：子模块更新增删 node/*.cpp 时自动重新 configure
file(GLOB core_src_glob CONFIGURE_DEPENDS ${CORE_DIR}/node/*.cpp)

set(src_files
    ${CORE_DIR}/osdep/OSUtils.cpp
    ${JNI_DIR}/com_zerotierone_sdk_Node.cpp
    ${JNI_DIR}/ZT_jniutils.cpp
    ${JNI_DIR}/ZT_jnicache.cpp)

# 注意：Metrics.cpp 必须参与编译（已在 GLOB 内）——它定义的计数器单例
# 被 Bond/Network/Node/Switch/Peer 等无守卫地引用；ZT_NO_PEER_METRICS
# 只关闭发布逻辑，剔除源文件会导致链接期 undefined symbol。
add_definitions(-DZT_NO_PEER_METRICS)

set(include_dirs
    ${CORE_DIR}/include/
    ${CORE_DIR}/node/
    ${CORE_DIR}/ext/
    ${CORE_DIR}/ext/prometheus-cpp-lite-1.0/core/include/
    ${CORE_DIR}/ext/prometheus-cpp-lite-1.0/simpleapi/include/)

include_directories(${include_dirs})

add_library(${PROJECT_NAME} SHARED ${src_files} ${core_src_glob})

set(link_libs)

if(ANDROID)
    # ZT_jniutils.h 中的 __android_log_print 需要 liblog
    find_library(log-lib log)
    set(link_libs ${link_libs} ${log-lib})
    # Android 15+ 可能使用 16KB 内存页，ELF LOAD 段必须 16KB 对齐，
    # 否则系统给出兼容性警告（未来版本将拒绝加载）。NDK r26 及更早
    # 默认 4KB，需显式指定。
    target_link_options(${PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")
endif()

target_link_libraries(${PROJECT_NAME} ${link_libs})
