# R8 / ProGuard 规则（release 已启用 minify）。
# kotlinx.serialization：依赖库自带 consumer 规则，这里补充本项目的 @Serializable DTO。
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-keep,includedescriptorclasses class com.smswebgateway.android.**$$serializer { *; }
-keepclassmembers class com.smswebgateway.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.smswebgateway.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Manifest 里按类名引用的组件由 AGP 自动生成 keep 规则；Room 的生成代码由 Room 自带规则处理。
# OkHttp / Okio 的 consumer 规则已随库提供。
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
