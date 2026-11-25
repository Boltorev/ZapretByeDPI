# Правила ProGuard/R8.
# На этапе 2 minifyEnabled = false, но правила уже готовы на будущее.

# JNI-класс hev: имена методов должны остаться Java_..._run/stop/stats.
-keep class com.zapret.byedpi.nativebridge.HevBridge { *; }
-keepclasseswithmembernames class * { native <methods>; }
