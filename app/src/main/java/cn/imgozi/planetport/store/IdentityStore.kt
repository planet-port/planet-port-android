package cn.imgozi.planetport.store

import android.util.Log
import java.io.File

/**
 * 节点身份模式的身份文件操作（App 侧管理；对齐 iOS IdentityStore.swift）。
 * 共享身份：files/zt/identity.*（全行星共用，默认）；
 * 每行星身份：files/zt/<planetId>/identity.*（核心首次在该行星以独立模式启动时
 * 自行生成；目录随删除行星级联清理，见 AppConfigStore.deletePlanet）。
 *
 * ZeroTier 核心只在构造（ZT_Node_new）时读/生成身份、退出不回写——身份复制
 * 全部由本 object 完成，唯一硬性顺序是复制必须先于新核心启动（调用方保证）。
 * 纯阻塞 File API，方法都很小（两个 <1KB 文件），调用方负责线程选择。
 */
object IdentityStore {
    private const val TAG = "IdentityStore"
    const val IDENTITY_PUBLIC = "identity.public"
    const val IDENTITY_SECRET = "identity.secret"

    /** 该行星是否已生成独立身份（以私钥文件为准）。 */
    fun hasPlanetIdentity(paths: StorePaths, planetId: String): Boolean =
        File(paths.planetStateDir(planetId), IDENTITY_SECRET).exists()

    /** 解析该行星独立身份的节点地址：identity.public 内容为 "address:publicKey"，
     *  冒号前 10 位 hex 即 ZeroTier 节点地址；无身份或不可解析返回 null。 */
    fun planetIdentityAddress(paths: StorePaths, planetId: String): String? =
        addressOf(File(paths.planetStateDir(planetId), IDENTITY_PUBLIC))

    /** 共享身份（files/zt/identity.public）的节点地址。共享模式下全行星
     *  卡片显示此地址，切换模式后无需等核心重连即可刷新显示。 */
    fun sharedIdentityAddress(paths: StorePaths): String? =
        addressOf(File(paths.dataStoreRoot, IDENTITY_PUBLIC))

    /** 全行星卡片身份地址：身份 ID 直连身份文件（ground truth）——共享模式
     *  全行星显示共享地址，独立模式各读各的 identity.public（未生成剔除，
     *  由 UI 显示占位）。身份模式切换后文件已就位，重读即刷新，不等核心
     *  重连回填（旧地址属于别的身份，不可作为显示来源）。 */
    fun planetNodeIds(
        paths: StorePaths,
        planets: List<PlanetProfile>,
        perPlanet: Boolean,
    ): Map<String, String> {
        val shared = if (perPlanet) null else sharedIdentityAddress(paths)
        return planets.mapNotNull { p ->
            (if (perPlanet) planetIdentityAddress(paths, p.id) else shared)
                ?.let { p.id to it }
        }.toMap()
    }

    private fun addressOf(file: File): String? =
        runCatching { file.readText() }
            .getOrNull()
            ?.split(":")
            ?.firstOrNull()
            ?.takeIf { it.length == 10 }

    /** 播种：把当前共享身份复制为指定行星的独立身份（已有独立身份或共享身份
     *  缺失时不动作）。切独立模式时让当前激活行星沿用现有身份，网络不中断。 */
    fun seedSharedIdentity(paths: StorePaths, planetId: String) {
        if (hasPlanetIdentity(paths, planetId)) return
        copyIdentity(from = paths.dataStoreRoot, to = paths.planetStateDir(planetId))
    }

    /** 采纳：把指定行星的独立身份复制为全局共享身份（覆盖原共享身份文件）。
     *  其余行星的独立身份文件原地保留，切回独立模式时原样恢复。 */
    fun adoptSharedIdentity(paths: StorePaths, planetId: String) {
        copyIdentity(from = paths.planetStateDir(planetId), to = paths.dataStoreRoot)
    }

    /** 成对复制身份文件（源缺失跳过；目标已存在先删除再复制——seed 场景由
     *  hasPlanetIdentity 挡，adopt 场景覆盖即语义）。失败记日志不抛（尽力而为：
     *  失败后独立模式该行星由核心生成全新身份、共享模式沿用原共享身份）。 */
    private fun copyIdentity(from: File, to: File) {
        for (name in listOf(IDENTITY_PUBLIC, IDENTITY_SECRET)) {
            val src = File(from, name)
            if (!src.exists()) continue
            runCatching {
                to.mkdirs()
                val dst = File(to, name)
                if (dst.exists()) dst.delete()
                src.copyTo(dst)
                if (name == IDENTITY_SECRET) {
                    // 私钥文件 owner-only（0600），写法对齐 NodeRuntime.StorePutListener
                    // 的 secure 分支：先全清再按属主放行（含 executable 清零），逐次幂等
                    dst.setReadable(false, false)
                    dst.setWritable(false, false)
                    dst.setExecutable(false, false)
                    dst.setReadable(true, true)
                    dst.setWritable(true, true)
                }
            }.onFailure { Log.w(TAG, "身份文件复制失败 $name", it) }
        }
    }
}
