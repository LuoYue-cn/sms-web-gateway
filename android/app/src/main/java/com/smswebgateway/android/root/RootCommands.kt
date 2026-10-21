package com.smswebgateway.android.root

/** Watchdog 模块与控制文件路径（ROOT_INTERFACE.md 第 1、5 节）。 */
object RootPaths {
    const val MODULE_ID = "smsgate_watchdog"
    const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    const val CONTROL_DIR = "/data/adb/smsgate"
    const val CONTROL_FILE = "$CONTROL_DIR/control"
    const val WATCHDOG_STATUS = "$CONTROL_DIR/watchdog.status"
    const val WATCHDOG_LOG = "$CONTROL_DIR/watchdog.log"
}

/**
 * Root 命令白名单（纯逻辑，可单测）。
 * 所有命令都由本文件里的常量/模板生成，参数只来自：BuildConfig 包名、整数、APK 自己的 cacheDir 路径；
 * 最后还要整串匹配 [ALLOWED]，任何不匹配的命令都会被 RootManager 拒绝执行。
 * 不存在 force-stop / pm disable / setenforce / settings put / kill / mount 等命令。
 */
object RootCommands {
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val STAGE_PATH = Regex("^/[A-Za-z0-9._/\\-]+$")

    const val ID = "id"
    const val GETPROP_BOOT = "getprop sys.boot_completed"
    const val GETPROP_ANDROID = "getprop ro.build.version.release"
    const val WHITELIST_LIST = "dumpsys deviceidle whitelist"
    const val MODULE_INSTALLED = "test -f ${RootPaths.MODULE_DIR}/module.prop"
    const val MODULE_MKDIR = "mkdir -p ${RootPaths.MODULE_DIR} ${RootPaths.CONTROL_DIR}"
    const val MODULE_CLEAR_MARKERS = "rm -f ${RootPaths.MODULE_DIR}/remove ${RootPaths.MODULE_DIR}/disable"
    const val MODULE_CHMOD = "chmod 755 ${RootPaths.MODULE_DIR}/*.sh"
    const val MODULE_CHMOD_PROP = "chmod 644 ${RootPaths.MODULE_DIR}/module.prop"
    const val MODULE_DISABLE = "touch ${RootPaths.MODULE_DIR}/disable"
    const val MODULE_ENABLE = "rm -f ${RootPaths.MODULE_DIR}/disable"
    const val MODULE_REMOVE = "touch ${RootPaths.MODULE_DIR}/remove"
    const val READ_WATCHDOG_STATUS = "cat ${RootPaths.WATCHDOG_STATUS}"
    const val READ_WATCHDOG_LOG = "tail -n 120 ${RootPaths.WATCHDOG_LOG}"

    fun whitelistAdd(pkg: String): String {
        require(PKG.matches(pkg)) { "invalid package" }
        return "dumpsys deviceidle whitelist +$pkg"
    }

    fun whitelistRemove(pkg: String): String {
        require(PKG.matches(pkg)) { "invalid package" }
        return "dumpsys deviceidle whitelist -$pkg"
    }

    /** 把 APK 自己解压到 cacheDir 的 Watchdog 模块内容复制到模块目录。 */
    fun copyModule(stageDir: String): String {
        require(STAGE_PATH.matches(stageDir) && !stageDir.contains("..")) { "invalid stage path" }
        return "cp -rf $stageDir/. ${RootPaths.MODULE_DIR}/"
    }

    /** 写 control。interval 限 30–300，maxFailures 限 1–20，resetTs 为秒（0 表示不写）。 */
    fun writeControl(enabled: Boolean, intervalS: Int, maxFailures: Int, pkg: String, resetTs: Long): String {
        require(PKG.matches(pkg)) { "invalid package" }
        val e = if (enabled) 1 else 0
        val i = intervalS.coerceIn(30, 300)
        val m = maxFailures.coerceIn(1, 20)
        val reset = if (resetTs > 0) "\\nreset_protection=${resetTs}" else ""
        return "printf 'enabled=$e\\ninterval=$i\\nmax_failures=$m\\npkg=$pkg$reset\\n' > ${RootPaths.CONTROL_FILE}"
    }

    /** 全部允许的命令形态。`su -v` 不经 `su -c`，由 RootManager 单独处理。 */
    val ALLOWED: List<Regex> = listOf(
        Regex("^id$"),
        Regex("^getprop (sys\\.boot_completed|ro\\.build\\.version\\.release)$"),
        Regex("^dumpsys deviceidle whitelist( [+-][A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+)?$"),
        Regex("^test -f ${Regex.escape(RootPaths.MODULE_DIR)}/module\\.prop$"),
        Regex("^mkdir -p ${Regex.escape(RootPaths.MODULE_DIR)} ${Regex.escape(RootPaths.CONTROL_DIR)}$"),
        Regex("^rm -f ${Regex.escape(RootPaths.MODULE_DIR)}/remove ${Regex.escape(RootPaths.MODULE_DIR)}/disable$"),
        Regex("^chmod 755 ${Regex.escape(RootPaths.MODULE_DIR)}/\\*\\.sh$"),
        Regex("^chmod 644 ${Regex.escape(RootPaths.MODULE_DIR)}/module\\.prop$"),
        Regex("^touch ${Regex.escape(RootPaths.MODULE_DIR)}/(disable|remove)$"),
        Regex("^rm -f ${Regex.escape(RootPaths.MODULE_DIR)}/disable$"),
        Regex("^cp -rf /[A-Za-z0-9._/\\-]+/\\. ${Regex.escape(RootPaths.MODULE_DIR)}/$"),
        Regex("^printf 'enabled=[01]\\\\ninterval=\\d{2,3}\\\\nmax_failures=\\d{1,2}\\\\npkg=[A-Za-z0-9._]+(\\\\nreset_protection=\\d+)?\\\\n' > ${Regex.escape(RootPaths.CONTROL_FILE)}$"),
        Regex("^cat ${Regex.escape(RootPaths.WATCHDOG_STATUS)}$"),
        Regex("^tail -n 120 ${Regex.escape(RootPaths.WATCHDOG_LOG)}$"),
    )

    private val FORBIDDEN_TOKENS = listOf(";", "&&", "||", "`", "$(", "\n", "|", "force-stop", "pm disable", "setenforce", "settings put", "mount", "kill")

    fun isAllowed(cmd: String): Boolean {
        if (cmd.length > 400) return false
        // printf 命令里的 `\n` 是字面反斜杠 n（给 printf 解释），真实换行/分号等一律拒绝
        if (FORBIDDEN_TOKENS.any { cmd.contains(it) }) return false
        return ALLOWED.any { it.matches(cmd) }
    }

    /** 解析 `dumpsys deviceidle whitelist` 输出：行形如 `user,com.pkg,10234` / `system-excidle,com.pkg,1000`。 */
    fun whitelistContains(output: String, pkg: String): Boolean =
        output.lineSequence().any { line ->
            val parts = line.trim().split(',')
            parts.size >= 2 && parts[1] == pkg
        }

    /** 判断 `id` 输出是否为 root。 */
    fun isRootId(output: String): Boolean = output.trimStart().startsWith("uid=0")

    /** 根据包名 / `su -v` 输出识别 Root 管理器。 */
    fun detectManager(installedPackages: Set<String>, suVersion: String?): String? {
        val v = suVersion.orEmpty()
        return when {
            "com.rifsxd.ksunext" in installedPackages -> "KernelSU Next"
            "me.weishu.kernelsu" in installedPackages -> "KernelSU"
            "me.bmax.apatch" in installedPackages -> "APatch"
            "com.topjohnwu.magisk" in installedPackages -> "Magisk"
            v.contains("KernelSU", true) -> "KernelSU"
            v.contains("APatch", true) -> "APatch"
            v.contains("MAGISK", true) -> "Magisk"
            v.isNotBlank() -> "其他（$v）".take(40)
            else -> null
        }
    }

    data class WatchdogStatusFile(val pid: Long?, val ts: Long?, val state: String?, val failures: Int?, val recoveryCount: Int?, val lastRecoveryTs: Long?)

    fun parseWatchdogStatus(text: String): WatchdogStatusFile {
        val m = text.lineSequence().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
        }.toMap()
        return WatchdogStatusFile(
            pid = m["pid"]?.toLongOrNull(), ts = m["ts"]?.toLongOrNull(), state = m["state"],
            failures = m["failures"]?.toIntOrNull(), recoveryCount = m["recovery_count"]?.toIntOrNull(),
            lastRecoveryTs = m["last_recovery_ts"]?.toLongOrNull(),
        )
    }

    /** Watchdog 是否在运行：状态文件足够新（3 个检查周期或至少 180 秒）且 state 不是 disabled。 */
    fun watchdogRunning(st: WatchdogStatusFile?, nowSec: Long, intervalS: Int): Boolean {
        if (st?.ts == null || st.state == null) return false
        if (st.state == "disabled") return false
        val fresh = maxOf(180, intervalS * 3)
        return nowSec - st.ts <= fresh
    }
}
