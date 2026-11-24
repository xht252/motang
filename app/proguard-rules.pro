# 墨唐 motang · R8 规则
# M0 只有 JNA + uniffi 生成的绑定需要保护。

# JNA direct mapping：反射注册 native 方法，禁止裁剪/混淆。
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }

# uniffi 生成的绑定通过 JNA 注册到 native 层，类名必须保持。
-keep class io.github.xht252.motang.core.** { *; }
-keepclassmembers class io.github.xht252.motang.core.** { *; }
