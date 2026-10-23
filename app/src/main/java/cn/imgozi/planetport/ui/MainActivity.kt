package cn.imgozi.planetport.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * P0 骨架验证页：确认 libZeroTierOneJNI.so 随 APK 分发且可加载。
 * P4 起替换为正式首页。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val jniLoaded = try {
            System.loadLibrary("ZeroTierOneJNI")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = if (jniLoaded) "libZeroTierOneJNI 加载成功" else "JNI 库加载失败")
                    }
                }
            }
        }
    }
}
