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

`../core/jni/`（本仓库自有维护位置）为官方 `java/jni` 绑定加
JNIEnvGuard 补丁，随 MPL-2.0 保留署名。
