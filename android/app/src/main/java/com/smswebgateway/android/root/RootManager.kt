package com.smswebgateway.android.root

import android.content.Context
import android.content.pm.PackageManager
import com.smswebgateway.android.BuildConfig
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.data.db.RootAuditEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class RootResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val rejected: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut && !rejected
}

data class OpResult(val ok: Boolean, val message: String)

/**
 * 统一的 Root 命令入口（规格 10.2 / 10.9，ROOT_INTERFACE.md 第 4、5 节）：
 *  - 只执行 [RootCommands] 里生成并通过白名单正则校验的命令；参数只来自包名常量 / 整数 / 本应用 cacheDir；
 *  - 每条命令 5–15 秒超时，捕获 exit code + stderr；
 *  - 每条命令写入 Room `root_audit`（心跳上报最近 ≤20 条摘要，不含短信内容或令牌）；
 *  - 所有「申请 Root」的入口都必须由用户点击触发（UI 层调用），本类不会在后台首次弹出授权。
 *  不 force-stop / pm disable、不碰 SELinux、不改系统分区、不 settings put、不 kill。
 */
object RootManager {
    private val mutex = Mutex()
    private val PKG = BuildConfig.APPLICATION_ID

    // ------------------------------------------------------------------ 底层执行

    private suspend fun exec(action: String, command: String, timeoutS: Long = 10): RootResult = withContext(Dispatchers.IO) {
        if (!RootCommands.isAllowed(command)) {
            audit(action, "rejected", null, "命令未通过白名单校验")
            AppLog.e("Root", "拒绝执行未通过白名单的命令（$action）")
            return@withContext RootResult(null, "", "command rejected by whitelist", timedOut = false, rejected = true)
        }
        runProcess(listOf("su", "-c", command), timeoutS).also { r ->
            val result = when {
                r.timedOut -> "timeout"
                r.exitCode == 0 -> "ok"
                else -> "fail"
            }
            audit(action, result, r.exitCode, if (r.ok) null else r.stderr.take(80))
        }
    }

    private fun runProcess(argv: List<String>, timeoutS: Long): RootResult {
        return try {
            val p = ProcessBuilder(argv).redirectErrorStream(false).start()
            p.outputStream.close()
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { runCatching { p.inputStream.bufferedReader().use { r -> out.append(r.readText().take(64 * 1024)) } } }
            val tErr = Thread { runCatching { p.errorStream.bufferedReader().use { r -> err.append(r.readText().take(8 * 1024)) } } }
            tOut.start(); tErr.start()
            val finished = p.waitFor(timeoutS.coerceIn(5, 15), TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                tOut.join(500); tErr.join(500)
                return RootResult(null, out.toString(), err.toString(), timedOut = true)
            }
            tOut.join(1000); tErr.join(1000)
            RootResult(p.exitValue(), out.toString(), err.toString(), timedOut = false)
        } catch (e: Exception) {
            RootResult(null, "", e.message.orEmpty().take(120), timedOut = false)
        }
    }

    private suspend fun audit(action: String, result: String, exitCode: Int?, detail: String?) {
        try {
            val dao = Graph.db.rootAudit()
            dao.insert(RootAuditEntity(ts = System.currentTimeMillis(), action = action, result = result, exitCode = exitCode, detail = detail))
            dao.prune()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ 检测 / 授权

    /** 通过已安装包识别 Root 管理器（不触发 su）。 */
    fun installedManagerPackages(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        return listOf("com.rifsxd.ksunext", "me.weishu.kernelsu", "com.topjohnwu.magisk", "me.bmax.apatch")
            .filter { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }
            .toSet()
    }

    /** 仅凭已安装包做出的 Root 管理器名称（不触发 su）。 */
    fun managerNameByPackages(ctx: Context): String? = RootCommands.detectManager(installedManagerPackages(ctx), null)

    /**
     * 申请 Root 权限（**必须由用户点击触发**）：执行 `id` 并确认 uid=0，再读取 `su -v` 识别管理器。
     */
    suspend fun requestRoot(ctx: Context): OpResult = mutex.withLock {
        val prefs = Graph.prefs
        val r = exec("root_check", RootCommands.ID, timeoutS = 15)
        if (!r.ok || !RootCommands.isRootId(r.stdout)) {
            prefs.rootGranted = false
            val why = when {
                r.timedOut -> "等待授权超时。若 Root 管理器弹出了请求，请允许本应用后重试。"
                r.exitCode == null && installedManagerPackages(ctx).isNotEmpty() ->
                    "本应用还没有被授予超级用户权限。KernelSU / APatch 不会弹窗询问，请打开 Root 管理器，在「超级用户」列表中开启本应用，然后重试。"
                r.exitCode == null -> "设备上找不到可用的 su，可能没有 Root。"
                else -> "Root 权限被拒绝。请在 Root 管理器中授予本应用超级用户权限。"
            }
            AppLog.w("Root", "Root 授权失败：exit=${r.exitCode}")
            return OpResult(false, why)
        }
        prefs.rootGranted = true
        val ver = runProcess(listOf("su", "-v"), 8).stdout.trim().lineSequence().firstOrNull()
        audit("su_version", if (ver.isNullOrBlank()) "fail" else "ok", 0, null)
        val name = RootCommands.detectManager(installedManagerPackages(ctx), ver)
        prefs.rootManagerName = name
        prefs.rootManagerVersion = ver?.take(60)
        AppLog.i("Root", "Root 已授权，管理器：${name ?: "未知"}")
        OpResult(true, "Root 已授权（${name ?: "未知管理器"}）")
    }

    /** Root 权限是否仍然有效。仅在用户已启用 Root 增强后才会被周期调用。失效 → 自动降级为普通模式。 */
    suspend fun verifyStillGranted(ctx: Context): Boolean = mutex.withLock {
        val prefs = Graph.prefs
        if (!prefs.rootEnabled) return true
        val r = exec("root_recheck", RootCommands.ID, timeoutS = 8)
        prefs.lastRootCheckAt = System.currentTimeMillis()
        if (r.ok && RootCommands.isRootId(r.stdout)) {
            prefs.rootGranted = true
            return true
        }
        AppLog.w("Root", "Root 权限已被撤销或不可用，降级为普通模式")
        prefs.rootGranted = false
        prefs.rootEnabled = false
        prefs.watchdogStatus = if (prefs.watchdogInstalled) "stopped" else "not_installed"
        // 无法再执行恢复命令，白名单原始状态保留在 prefs 中，待用户重新授权后可「恢复系统原始设置」
        audit("root_revoked", "downgraded", r.exitCode, null)
        com.smswebgateway.android.service.Notifications.alert(ctx, "Root 权限已失效", "已自动降级为普通保活模式。可在「设置 → Root 保活管理」中重新授权。")
        false
    }

    /** 周期维护（仅在用户已启用 Root 增强后调用）：确认 Root 仍有效，并刷新 Watchdog 状态。 */
    suspend fun maintain(ctx: Context) {
        val prefs = Graph.prefs
        if (!prefs.rootEnabled) return
        if (System.currentTimeMillis() - prefs.lastRootCheckAt < 4 * 60_000L) return
        if (verifyStillGranted(ctx)) refreshWatchdog(ctx)
    }

    // ------------------------------------------------------------------ 电池白名单

    private suspend fun whitelistState(): Boolean? {
        val r = exec("whitelist_query", RootCommands.WHITELIST_LIST)
        return if (r.ok) RootCommands.whitelistContains(r.stdout, PKG) else null
    }

    /** 加入电池白名单：先记录原始状态；执行后复查。 */
    private suspend fun addToWhitelist(): OpResult {
        val prefs = Graph.prefs
        val before = whitelistState() ?: return OpResult(false, "无法读取电池白名单状态")
        if (!prefs.wlOriginalKnown) {
            prefs.wlOriginalKnown = true
            prefs.wlOriginalPresent = before
        }
        if (before) {
            prefs.batteryWhitelisted = true
            return OpResult(true, "应用已在电池白名单中（保持原状）")
        }
        val r = exec("whitelist_add", RootCommands.whitelistAdd(PKG))
        val after = whitelistState()
        return if (r.ok && after == true) {
            prefs.wlAddedByUs = true
            prefs.batteryWhitelisted = true
            AppLog.i("Root", "已加入电池白名单")
            OpResult(true, "已加入电池优化白名单")
        } else {
            AppLog.w("Root", "加入白名单未生效 exit=${r.exitCode}")
            OpResult(false, "加入白名单未生效（该 ROM 可能限制了此命令）")
        }
    }

    /** 还原白名单：仅当本应用加入且原本不在白名单时才移除。 */
    private suspend fun restoreWhitelist(): OpResult {
        val prefs = Graph.prefs
        if (!prefs.wlAddedByUs) return OpResult(true, "白名单无需还原")
        if (prefs.wlOriginalKnown && prefs.wlOriginalPresent) {
            prefs.wlAddedByUs = false
            return OpResult(true, "原本就在白名单中，不移除")
        }
        val r = exec("whitelist_remove", RootCommands.whitelistRemove(PKG))
        val after = whitelistState()
        return if (r.ok && after == false) {
            prefs.wlAddedByUs = false
            prefs.wlOriginalKnown = false
            prefs.batteryWhitelisted = false
            AppLog.i("Root", "已从电池白名单还原")
            OpResult(true, "已从电池白名单移除（还原原始状态）")
        } else OpResult(false, "还原白名单失败，请稍后重试")
    }

    // ------------------------------------------------------------------ Watchdog 模块

    fun bundledModuleAvailable(ctx: Context): Boolean =
        runCatching { ctx.assets.open("root-module.zip").close(); true }.getOrDefault(false)

    private fun controlCommand(enabled: Boolean, resetTs: Long = 0): String {
        val p = Graph.prefs
        return RootCommands.writeControl(enabled, p.watchdogIntervalS, p.watchdogMaxFailures, PKG, resetTs)
    }

    /** 把 assets/root-module.zip 解压到 cacheDir，再用 root 复制到 /data/adb/modules/smsgate_watchdog/。 */
    suspend fun installWatchdog(ctx: Context): OpResult = mutex.withLock {
        val prefs = Graph.prefs
        if (!prefs.rootGranted) return OpResult(false, "请先授予 Root 权限")
        if (!bundledModuleAvailable(ctx)) {
            return OpResult(false, "此 APK 未内置 Watchdog 模块（构建时 ../root-module/ 不存在）。请重新构建，或手动刷入 smsgate-watchdog.zip。")
        }
        val stage = File(ctx.cacheDir, "wd_stage")
        try {
            withContext(Dispatchers.IO) {
                stage.deleteRecursively()
                stage.mkdirs()
                extractZip(ctx, stage)
            }
        } catch (e: Exception) {
            AppLog.e("Root", "解压模块失败", e)
            return OpResult(false, "解压内置模块失败：${e.message.orEmpty().take(60)}")
        }
        val steps = listOf(
            "module_mkdir" to RootCommands.MODULE_MKDIR,
            "module_copy" to RootCommands.copyModule(stage.absolutePath),
            "module_chmod" to RootCommands.MODULE_CHMOD,
            "module_chmod_prop" to RootCommands.MODULE_CHMOD_PROP,
            "module_clear_markers" to RootCommands.MODULE_CLEAR_MARKERS,
            "control_write" to controlCommand(enabled = prefs.rootEnabled),
        )
        for ((action, cmd) in steps) {
            val r = exec(action, cmd, timeoutS = 15)
            if (!r.ok) {
                withContext(Dispatchers.IO) { stage.deleteRecursively() }
                return OpResult(false, "安装失败（$action，exit=${r.exitCode}）：${r.stderr.take(80)}")
            }
        }
        withContext(Dispatchers.IO) { stage.deleteRecursively() }
        val installed = exec("module_verify", RootCommands.MODULE_INSTALLED).ok
        prefs.watchdogInstalled = installed
        prefs.watchdogStatus = if (installed) "stopped" else "not_installed"
        if (installed) AppLog.i("Root", "Watchdog 模块已部署，重启后由 Root 管理器加载（或启用 Root 增强后即用）")
        if (installed) OpResult(true, "Watchdog 模块已安装。需重启手机后由 Root 管理器加载并启动守护。")
        else OpResult(false, "安装后校验失败")
    }

    private fun extractZip(ctx: Context, dest: File) {
        val base = dest.canonicalFile
        ZipInputStream(ctx.assets.open("root-module.zip")).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                val out = File(dest, e.name).canonicalFile
                // 防 zip slip
                require(out.path.startsWith(base.path + File.separator) || out == base) { "非法条目" }
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
                e = zin.nextEntry
            }
        }
    }

    /** 启用 / 禁用已安装的 Watchdog 模块（禁用 = 写 enabled=0 + 标准 disable 标记）。 */
    suspend fun setWatchdogModuleEnabled(enabled: Boolean): OpResult = mutex.withLock {
        val prefs = Graph.prefs
        if (!prefs.rootGranted) return OpResult(false, "请先授予 Root 权限")
        if (!exec("module_check", RootCommands.MODULE_INSTALLED).ok) {
            prefs.watchdogInstalled = false
            prefs.watchdogStatus = "not_installed"
            return OpResult(false, "Watchdog 模块尚未安装")
        }
        if (enabled) {
            exec("module_enable", RootCommands.MODULE_ENABLE)
            val ok = exec("control_write", controlCommand(enabled = true)).ok
            OpResult(ok, if (ok) "已启用 Watchdog（守护将在下一个检查周期生效）" else "写入控制文件失败")
        } else {
            val ok1 = exec("control_write", controlCommand(enabled = false)).ok
            val ok2 = exec("module_disable", RootCommands.MODULE_DISABLE).ok
            prefs.watchdogStatus = "stopped"
            OpResult(ok1 && ok2, if (ok1 && ok2) "已禁用 Watchdog" else "禁用未完全成功")
        }
    }

    /** 读取并解析 watchdog.status，刷新缓存状态。 */
    suspend fun refreshWatchdog(ctx: Context): String = mutex.withLock {
        val prefs = Graph.prefs
        if (!prefs.rootGranted) {
            return prefs.watchdogStatus ?: if (prefs.watchdogInstalled) "stopped" else "not_installed"
        }
        val installed = exec("module_check", RootCommands.MODULE_INSTALLED).ok
        prefs.watchdogInstalled = installed
        if (!installed) {
            prefs.watchdogStatus = "not_installed"
            return "not_installed"
        }
        val r = exec("watchdog_status", RootCommands.READ_WATCHDOG_STATUS)
        val st = if (r.ok) RootCommands.parseWatchdogStatus(r.stdout) else null
        val running = RootCommands.watchdogRunning(st, System.currentTimeMillis() / 1000, prefs.watchdogIntervalS)
        val status = if (running) "running" else "stopped"
        prefs.watchdogStatus = status
        prefs.watchdogStatusAt = System.currentTimeMillis()
        st?.lastRecoveryTs?.takeIf { it > 0 }?.let { ts ->
            // 取 APK 本地记录与 Watchdog 记录中较新的
            if (ts * 1000 > prefs.lastRecoveryAt) prefs.lastRecoveryAt = ts * 1000
        }
        status
    }

    suspend fun readWatchdogLog(): String = mutex.withLock {
        if (!Graph.prefs.rootGranted) return "（未授予 Root 权限，无法读取 Watchdog 日志）"
        val r = exec("watchdog_log", RootCommands.READ_WATCHDOG_LOG)
        if (r.ok) r.stdout.ifBlank { "（Watchdog 日志为空）" } else "（读取失败：exit=${r.exitCode}）"
    }

    /** 用户点击「重置保护」：APK 写 control 的 reset_protection=<ts>，同时清零本地保护计数。 */
    suspend fun resetProtection(): OpResult = mutex.withLock {
        val prefs = Graph.prefs
        prefs.guardFailures = 0
        prefs.guardLastAttemptAt = 0
        if (!prefs.rootGranted) return OpResult(true, "已重置本地保护状态")
        val ts = System.currentTimeMillis() / 1000
        prefs.resetProtectionTs = ts
        val ok = exec("reset_protection", controlCommand(Graph.prefs.rootEnabled, resetTs = ts)).ok
        OpResult(ok, if (ok) "已重置保护状态" else "写入控制文件失败")
    }

    // ------------------------------------------------------------------ 启用 / 关闭 Root 增强

    /** 启用 Root 增强（用户点击触发）：授权 → 加入电池白名单（记录原始状态）→ 若已安装模块则启用守护。 */
    suspend fun enableEnhanced(ctx: Context): OpResult {
        val grant = requestRoot(ctx)
        if (!grant.ok) return grant
        return mutex.withLock {
            val prefs = Graph.prefs
            val wl = addToWhitelist()
            prefs.rootEnabled = true
            val installed = exec("module_check", RootCommands.MODULE_INSTALLED).ok
            prefs.watchdogInstalled = installed
            var msg = "Root 增强已启用。" + wl.message
            if (installed) {
                exec("module_enable", RootCommands.MODULE_ENABLE)
                val ok = exec("control_write", controlCommand(enabled = true)).ok
                msg += if (ok) " Watchdog 已启用。" else " 写入 Watchdog 控制文件失败。"
            } else {
                prefs.watchdogStatus = "not_installed"
                msg += " 尚未安装 Watchdog 模块（可选）。"
            }
            AppLog.i("Root", "Root 增强已启用")
            OpResult(true, msg)
        }
    }

    /**
     * 关闭 Root 增强：停止守护（control enabled=0）并还原原始设置（白名单）。Root 已失效时仅复位本地状态。
     */
    suspend fun disableEnhanced(ctx: Context, reason: String = "user"): OpResult = mutex.withLock {
        val prefs = Graph.prefs
        val notes = mutableListOf<String>()
        var rootUsable = false
        if (prefs.rootGranted || prefs.rootEnabled || prefs.watchdogInstalled || prefs.wlAddedByUs) {
            rootUsable = exec("root_check", RootCommands.ID, timeoutS = 8).let { it.ok && RootCommands.isRootId(it.stdout) }
        }
        if (rootUsable) {
            if (exec("module_check", RootCommands.MODULE_INSTALLED).ok || prefs.watchdogInstalled) {
                val ok = exec("control_write", controlCommand(enabled = false)).ok
                notes += if (ok) "已停止 Watchdog 守护（enabled=0）" else "写入 enabled=0 失败"
            }
            val wl = restoreWhitelist()
            notes += wl.message
        } else {
            notes += "Root 不可用，仅复位本地状态"
        }
        prefs.rootEnabled = false
        if (!rootUsable) prefs.rootGranted = false
        prefs.watchdogStatus = if (prefs.watchdogInstalled) "stopped" else "not_installed"
        AppLog.i("Root", "Root 增强已关闭（$reason）")
        OpResult(true, notes.joinToString("；"))
    }

    /** 「恢复系统原始设置」：关闭 Root 增强，并确保白名单回到原始状态。 */
    suspend fun restoreOriginalSettings(ctx: Context): OpResult = disableEnhanced(ctx, reason = "restore")
}
