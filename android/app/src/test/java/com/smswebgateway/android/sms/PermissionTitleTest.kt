package com.smswebgateway.android.sms

import org.junit.Assert.assertFalse
import org.junit.Test

class PermissionTitleTest {
    @Test fun titlesDoNotCarryTheOptionalSuffix() {
        // 权限页会给非必需权限统一追加「（可选）」；标题自己再带一个就会显示成「（可选）（可选）」
        AppPermission.values().forEach {
            assertFalse("${it.name} 的标题不应包含（可选）", it.title.contains("可选"))
        }
    }
}
