package com.smswebgateway.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import com.smswebgateway.android.ui.screens.HomeScreen
import com.smswebgateway.android.ui.screens.LogsScreen
import com.smswebgateway.android.ui.screens.PairScreen
import com.smswebgateway.android.ui.screens.PermissionsScreen
import com.smswebgateway.android.ui.screens.RootScreen
import com.smswebgateway.android.ui.screens.ScanScreen
import com.smswebgateway.android.ui.screens.SettingsScreen
import com.smswebgateway.android.ui.screens.UnboundScreen

enum class Route { Home, Permissions, Settings, Root, Logs, Pair, Scan }

/** 极简的栈式导航（页面数量少，不需要 NavHost）。未绑定时只显示 未绑定 / 手动输入 / 扫码 / 权限。 */
@Composable
fun AppRoot(vm: AppViewModel) {
    val state by vm.ui.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var stack by remember { mutableStateOf(listOf(Route.Home)) }
    var scanned by remember { mutableStateOf<Pair<String, String>?>(null) }

    val route = stack.last()
    fun push(r: Route) { stack = stack + r }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1) { pop() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.toast(null) }
    }
    // 解绑 / 被吊销后回到根页
    LaunchedEffect(state.bound) { if (!state.bound) stack = listOf(Route.Home) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Box(Modifier.fillMaxSize()) {
            val mod = Modifier.padding(pad)
            when {
                route == Route.Scan -> ScanScreen(
                    modifier = mod,
                    onResult = { url, tok ->
                        scanned = url to tok
                        pop()
                        // 扫码成功即直接配对。失败时才进入手动页，并带着已填好的地址和令牌，便于查看原因。
                        vm.pair(url, tok) { ok ->
                            if (ok) {
                                scanned = null
                                stack = listOf(Route.Home)
                            } else if (stack.last() != Route.Pair) {
                                push(Route.Pair)
                            }
                        }
                    },
                    onBack = { pop() },
                )
                route == Route.Pair -> PairScreen(
                    modifier = mod, vm = vm, initial = scanned, busy = busy,
                    onScan = { push(Route.Scan) },
                    onDone = { scanned = null; stack = listOf(Route.Home) },
                    onBack = { scanned = null; pop() },
                )
                route == Route.Permissions -> PermissionsScreen(mod, vm, state, onBack = { pop() })
                !state.bound -> UnboundScreen(
                    modifier = mod, state = state,
                    onScan = { push(Route.Scan) }, onManual = { push(Route.Pair) }, onPermissions = { push(Route.Permissions) },
                )
                route == Route.Settings -> SettingsScreen(mod, vm, state, busy, onRoot = { push(Route.Root) }, onLogs = { push(Route.Logs) },
                    onPermissions = { push(Route.Permissions) }, onBack = { pop() })
                route == Route.Root -> RootScreen(mod, vm, state, busy, onLogs = { push(Route.Logs) }, onBack = { pop() })
                route == Route.Logs -> LogsScreen(mod, onBack = { pop() })
                else -> HomeScreen(mod, vm, state, onSettings = { push(Route.Settings) }, onPermissions = { push(Route.Permissions) }, onLogs = { push(Route.Logs) })
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}
