# Vendored ZeroTier Core

ZeroTier 1.16.2 官方源码的 vendored 子集，来源：

- 仓库：https://github.com/zerotier/ZeroTierOne
- 版本：1.16.2（commit fe29cd88886e4f58547ba7f740b2d73eb49ab222）

携带目录（Android JNI 构建所需的最小集合）：`node/`、`include/`、
`version.h`、`osdep/`、`java/src/`、`ext/prometheus-cpp-lite-1.0/`、
`ext/x64-salsa2012-asm/`、`ext/arm32-neon-salsa2012-asm/`、
`ext/nlohmann/`（`osdep/OSUtils.hpp` 依赖，MIT，见其 LICENSE.MIT）。

协议：MPL-2.0（见 `LICENSE-MPL.txt` 与 `LICENSE.txt`）。本目录内文件
保持零修改；升级方式 = 从上游 tag 重新拷贝上述目录 + 全量构建验证。

`../core/jni/`（本仓库自有维护位置）为官方 1.16.2 `java/jni` 六文件：
五个原样；`ZT_jniutils.h` 唯一自维护修改——GETENV 宏改用本仓库自维护的
`ZT_jniguard.h` 守卫（按需 Attach/Detach，防核心原生线程回调在 ICMPv6
NS 路径崩溃），修改版源码随本仓库开源履行 MPL-2.0。

注：官方 `java/` 源文件头为历史遗留的 GPLv3 样板，仓库级 `LICENSE.txt`
（fe29cd8）声明除 `ext/` 与 `nonfree/` 外均为 MPL-2.0，本仓库以仓库级
声明为准。
