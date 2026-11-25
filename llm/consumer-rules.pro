# :llm 模块的 R8 规则（M5 逐步补齐）
# JNI 方法由 native 侧按名字绑定，必须保留。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class io.github.xht252.motang.llm.** { *; }
