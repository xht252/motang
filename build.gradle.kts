// 墨唐 motang · 根构建脚本
// 只声明插件版本，不在此处 apply；实际 apply 在各模块。
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.ksp) apply false
}
