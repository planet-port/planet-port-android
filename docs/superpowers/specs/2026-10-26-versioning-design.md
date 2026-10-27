# 版本号方案设计

日期：2026-10-26
状态：已确认

## 背景

项目版本号在 `app/build.gradle.kts` 中写死为 `0.1.0` / `1`，无 git tag、无 CHANGELOG。
目标：与 planet-port-ios 建立同一套版本管理逻辑，git-cliff + Conventional Commits 自动推导，本地一条命令发版。

## 决策

- **起点版本**：`1.0.0`（与 iOS 基线对齐，核心功能已完整，两端版本号同步演进便于对应功能批次）。
- **版本方案**：语义化版本（SemVer），取其精神不取教条：
  - MAJOR：大改版 / 不兼容变更（如配置格式、数据迁移）
  - MINOR：新功能
  - PATCH：修复
- **事实源**：git tag（`vX.Y.Z` 格式）。`app/build.gradle.kts` 中的版本只是 Android 构建要求的投影。
- **自动化工具**：git-cliff（MIT/Apache-2.0，Rust 生态，本地运行，不依赖 CI）。
  以后接入 CI 时可在同一套 Conventional Commits 基础上升级。
- **版本计算**：由 git-cliff 从上次 tag 之后的 Conventional Commits 自动推导
  （`feat!`/`BREAKING CHANGE` → MAJOR，`feat` → MINOR，其余 → PATCH），
  发版者不手工判断版本号。

## 发版流程

`./scripts/release.sh` 一条命令完成：

1. `git-cliff --bumped-version` 从上次 tag 之后的提交算出下一版本
2. `git-cliff --bump -o CHANGELOG.md` 生成中文更新日志（`chore` 类提交不入日志）
3. 同步 `app/build.gradle.kts`：
   - `versionName` = 新版本
   - `versionCode` = 递增整数（单调递增，Android 要求整型）
4. `git commit` + `git tag vX.Y.Z`

日常开发无需任何额外操作，保持现有 Conventional Commits 规范即可。

## 文件清单

| 文件 | 作用 |
|---|---|
| `cliff.toml` | git-cliff 配置：解析 Conventional Commits，中文分组输出 |
| `scripts/release.sh` | 发版脚本 |
| `CHANGELOG.md` | 更新日志（v1.0.0 起记录） |
| `app/build.gradle.kts` | App 版本（versionName/versionCode） |

## 基线（本次执行）

- `app/build.gradle.kts`：`versionName` `0.1.0` → `1.0.0`，`versionCode` 保持 `1`
- 生成 `CHANGELOG.md`，全部历史提交归入 `v1.0.0` 条目
- 提交后打 `v1.0.0` tag

## 后续可选升级（现在不做）

- 接入 CI 后改用 tag 触发自动构建（如 Gradle + F-Droid / Play 上传流水线）
