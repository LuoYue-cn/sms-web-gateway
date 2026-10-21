package com.smswebgateway.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.smswebgateway.android.keepalive.ForegroundServiceStarter
import com.smswebgateway.android.ui.AppRoot
import com.smswebgateway.android.ui.AppViewModel
import com.smswebgateway.android.ui.theme.SmsGateTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmsGateTheme {
                Surface(color = MaterialTheme.colorScheme.background) { AppRoot(vm) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 界面在前台，系统允许启动前台服务。用户主动停止过的不自动拉起。
        val p = Graph.prefs
        if (p.isBound && !p.userStopped) ForegroundServiceStarter.start(this)
        vm.refreshNow()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshNow()
    }
}
