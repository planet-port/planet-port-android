# 更新日志

所有显著变更均记录于此。版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## v1.0.0（2026-10-24）
### CI

- GitHub Actions 构建流水线
### 修复

- **core：**清除 kaaass 血统 JNI 补丁，改用自维护 GETENV 守卫
- **store：**域级读-改-写互斥，防止并发覆盖丢更新
- **store：**终审收口——official 档案保留、自检补 networks 域、IO 收敛
- **tunnel：**数据面修复波——广播 MAC 常量、NDP 报文布局、诊断格式
- **tunnel：**数据面审查修复——TUN 写流生命周期与前缀守卫
- **tunnel：**启动期资源收口与被踢网络快照清理
- **tunnel：**NodeRuntime start/shutdown 加互斥防并发双清 native 崩溃
- **tunnel：**establish 幂等闸门防 CONFIG_UPDATE 周期性重建 TUN
- **tunnel：**T6 审查修复——组播订阅移出幂等闸门与 onRevoke 停机
- **tunnel：**停机路径收敛全部 JNI 进入者与写任务竞态护随
- **tunnel：**NDP 出站合规——ICMPv6 校验和、组播目的与本机源地址
- **tunnel：**被踢网络拆 TUN 与停机窄窗收口四项
- **tunnel：**拆 TUN 与停机闩锁解耦——恢复路径重建不被阻断
- **tunnel：**Failed 态下停止门面放行——消除 App 内停不掉的死锁
- **ui：**审查修复——IPv4 键盘点号、冲突判定条件与 DNS 校验语义
- **tunnel：**Builder 组装与 establish 包 try-catch 防崩
- **tunnel：**establish 移入 NonCancellable 上下文防 fd 失主
- **tunnel：**拆 TUN 保持 Running 态并把常驻通知标注无网络
- **tunnel：**启停意图改走 startForegroundService 并收口前台义务
- **store：**planet 下载上限与 NS 应答 DAD 形态收口
- **ui：**审查自检修复——拾色预览与落盘分离、CAS 循环去挂起、图标弹层布局
- **ui：**卫星页与 Peers 页审查修复——异常兜底、对齐 iOS 替换与优选路径语义
- **ui：**修正网络详情页状态文案函数旧名引用
- **ui：**偏好写入改乐观更新消除主题一步滞后
- **ui：**加入网络去保存侧上限阻断并补整卡点击选中行星
- **ui：**偏好落盘改回字段级读改写防指针回退
- **ui：**官方磁贴圆角改百分比自适应并清理搬移残留 import
- **ui：**首页头区 overlay 化与 FAB 拖动吸附
- **home：**头区渐变改三段 colorStops 对齐 iOS mask stops
- **qr：**竖屏扫码帧旋转越界崩溃，改手动旋转 Y 平面
- **ui：**修复 T7 审查三项 Important 问题
- **home：**网络计数行文案接入三语资源
- **tunnel：**常驻通知与隧道失败原因接入字符串资源
- **home：**头区 chip 与圆点标签官方行星名显示侧本地化
- **ui：**写盘失败技术串不再透出用户，查重文案改专用类型映射
- **ui：**修复 P4 终审三项必改问题
### 文档

- Planet Port Android v2 重写设计文档
- 设计文档改为全新项目口径
- 参考源唯一化为 iOS 姊妹项目 planet-port-i
- vendor 清单补记 ext/nlohmann 头库
- P0 实施计划入库并修正 vendor 清单与体积描述
- **plans：**P0 计划补废止注记，防止按原步骤引回已清除的 JNI 补丁
- 制定 P1 数据层实施计划并同步 iOS 项目目录更名
- **plan：**P1 计划补域级互斥设计并修正 StorePaths 构造参数
- **plan：**修正计划代码中 resetAllNetworkActivation 的早退写法
- **plan：**修正导入器计划代码两处 Kotlin 语法缺陷
- **plan：**同步 P1 终审修复波至计划文件
- **plan：**P2 隧道核心实施计划
- **plan：**P2 计划回写实施差异记录
- **plan：**补总体路线图——六阶段定义、进度与跨阶段移交汇总
- **plan：**P3 多网络数据面实施计划
- **plan：**P3 代码面收尾——路线图更新与终审必验项回写
- **plan：**P4 UI 全量实施计划
- **tunnel：**停机 JNI 任务清单补 settingsJob
- **tunnel：**restartIfRunning 注释对齐 disableIpv6 热重配现状
- **plan：**P4 计划追加 T6b 冲突链路任务并更新波次编排
- **plan：**P4 阶段闭合与移交表状态更新
### 新增

- app 模块骨架与 JNI 库加载验证
- **store：**数据模型与 JSON 原子读写设施
- **store：**四份 JSON 配置的域 API 与切换/级联删除
- **store：**planet 与 moon 文件导入解析
- **app：**P1 数据层真机自检页
- **tunnel：**服务底座——VpnService 壳、状态总线与前台通知
- **tunnel：**NodeRuntime——核心生命周期与 dataStore hook
- **tunnel：**UdpCom——UDP 9994 物理收发与 protect 防环回
- **tunnel：**数据面——帧转换六件套与 TUN 读写
- **tunnel：**TunnelManager——netconf 聚合与 TUN 建立
- **ui：**P2 验证页——启停/join/状态观察与端到端闭环
- **tunnel：**状态总线补强与数据层收敛
- **ui：**主题系统与通用组件基建
- **tunnel：**appliedEntries 纳入 disableIpv6 走热重配
- **ui：**卫星管理页
- **ui：**Peers 列表页
- **ui：**主页行星与网络展示组件
- **ui：**主页编排 HomeViewModel
- **ui：**主页框架接入应用根路由
- **ui：**网络详情页与 Form 节组件
- **ui：**全局偏好设置页
- **ui：**行星设置页与图标选择弹层
- **ui：**数据面诊断页
- **ui：**波 2 合流接线——全页路由、主题启动 seed 与图标体系统一
- **tunnel：**冲突检测结果推流总线并在停机时清空
- **ui：**冲突网络自动断开收敛并接通卡片实时冲突态
- **ui：**首页网络计数行剔除冲突网络
- **ui：**应用三语本地化
- **ui：**首页背景图与重力视差
- **ui：**扫码加入网络
- **home：**首页七处用户可见文案接入字符串资源
- **ui：**官方行星名改为显示侧本地化
### 构建

- 初始化 Gradle 工具链与工程骨架
- vendor 引入 ZeroTier 1.16.2 核心源码
- externals 补全 ext/nlohmann 头库
- core 模块 JNI 绑定与 NDK 构建打通
- 补 CI SDK 预装备注、修正 srcDirs 语义与 .gitignore
- 接入 kotlinx-serialization 与 coroutines 依赖
### 重构

- **store：**导入器错误消息改稳定标识串并由消费侧资源化

