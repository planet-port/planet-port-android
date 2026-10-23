package cn.imgozi.planetport.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * P1 数据层自检页：JNI 加载 + store 全链路（隔离目录）逐项结果。
 * P4 起替换为正式首页。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val jniLoaded = try {
            System.loadLibrary("ZeroTierOneJNI")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libZeroTierOneJNI 加载失败", e)
            false
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val results by produceState(initialValue = listOf("自检中…")) {
                        value = try {
                            P1SelfCheck.run(this@MainActivity)
                        } catch (e: Exception) {
                            listOf("自检异常: ${e.message}")
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(modifier = Modifier.height(48.dp))
                        Text(text = if (jniLoaded) "libZeroTierOneJNI 加载成功" else "JNI 库加载失败")
                        results.forEach { Text(text = it) }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
