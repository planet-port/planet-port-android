package cn.imgozi.planetport.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import cn.imgozi.planetport.store.StoreRepo

class MainActivity : ComponentActivity() {
    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var vpnGrantedCallback: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // JNI 加载兜底（P1 保留）：加载失败仅记日志，不阻塞页面
        try {
            System.loadLibrary("ZeroTierOneJNI")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libZeroTierOneJNI 加载失败", e)
        }

        // store 进程单例首处初始化（P4 收敛：UI 与隧道共用同一 AppConfigStore 实例）
        StoreRepo.init(this)

        vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                vpnGrantedCallback?.invoke()
                vpnGrantedCallback = null
            }
        }
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {}

        // API 33+ 通知运行时权限（FGS 通知可见性；拒绝不阻塞功能）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface {
                    P2SelfCheckScreen(this)
                }
            }
        }
    }

    /** VPN 系统授权（prepare 返回 null 表示已授权过，直接执行） */
    fun requestVpnPermission(onGranted: () -> Unit) {
        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            onGranted()
        } else {
            vpnGrantedCallback = onGranted
            vpnPermissionLauncher.launch(prepare)
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
