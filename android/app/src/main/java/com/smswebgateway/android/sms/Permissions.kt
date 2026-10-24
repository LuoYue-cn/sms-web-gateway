package com.smswebgateway.android.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** 运行时权限定义与检查。 */
enum class AppPermission(
    val permission: String,
    val title: String,
    val purpose: String,
    val required: Boolean,
) {
    READ_SMS(Manifest.permission.READ_SMS, "读取短信", "同步手机里的历史短信到服务器", true),
    RECEIVE_SMS(Manifest.permission.RECEIVE_SMS, "接收短信", "实时接收新短信并上传", true),
    SEND_SMS(Manifest.permission.SEND_SMS, "发送短信", "执行网页下发的发送任务", true),
    READ_PHONE_STATE(Manifest.permission.READ_PHONE_STATE, "读取 SIM 信息", "识别双卡槽位、运营商，指定 SIM 发送", true),
    READ_PHONE_NUMBERS(Manifest.permission.READ_PHONE_NUMBERS, "读取本机号码（可选）", "在网页里显示每张 SIM 的手机号码；读不到时可在网页手动填写", false),
    READ_CONTACTS(Manifest.permission.READ_CONTACTS, "读取联系人（可选）", "在网页里显示联系人姓名", false),
    POST_NOTIFICATIONS(Manifest.permission.POST_NOTIFICATIONS, "通知", "显示前台服务常驻通知与异常提示", false),
    ;

    fun isGranted(ctx: Context): Boolean {
        if (this == POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
    }
}

object Permissions {
    fun has(ctx: Context, p: AppPermission) = p.isGranted(ctx)
    fun hasReadSms(ctx: Context) = AppPermission.READ_SMS.isGranted(ctx)
    fun hasSendSms(ctx: Context) = AppPermission.SEND_SMS.isGranted(ctx)
    fun hasPhoneState(ctx: Context) = AppPermission.READ_PHONE_STATE.isGranted(ctx)
    fun hasPhoneNumbers(ctx: Context) = AppPermission.READ_PHONE_NUMBERS.isGranted(ctx)
    fun hasContacts(ctx: Context) = AppPermission.READ_CONTACTS.isGranted(ctx)

    fun missingRequired(ctx: Context): List<AppPermission> =
        AppPermission.values().filter { it.required && !it.isGranted(ctx) }

    fun allRequiredGranted(ctx: Context) = missingRequired(ctx).isEmpty()
}
