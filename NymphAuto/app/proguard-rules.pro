# kotlinx-serialization keep rules
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class dev.nymph.auto.**$$serializer { *; }
-keepclassmembers class dev.nymph.auto.** {
    *** Companion;
}
-keepclasseswithmembers class dev.nymph.auto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn kotlinx.serialization.**

# 无障碍服务/输入法由系统反射绑定，不可混淆
-keep class dev.nymph.auto.engine.AutoClickService { *; }
-keep class dev.nymph.auto.engine.ImeService { *; }
-keep class dev.nymph.auto.service.RunnerService { *; }

# QuickJS JNI 绑定（接入后启用）
# -keep class * implements dev.nymph.auto.runner.QuickJsBinding { *; }
