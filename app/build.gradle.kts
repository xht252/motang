import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// Rust / uniffi 接线
// ---------------------------------------------------------------------------
// 设计要点：
// 1) debug 与 release 分目录产出 .so，互不覆盖：build/rustJniLibs/{debug,release}
// 2) 绑定从 debug（未 strip）的 x86_64 产物生成，规避 strip 后符号缺失。
// 3) release 只打 arm64-v8a；debug 额外带 x86_64 供模拟器。
val rustProjectDir = rootProject.layout.projectDirectory.dir("core-rs")
val rustJniDebugDir = layout.buildDirectory.dir("rustJniLibs/debug")
val rustJniReleaseDir = layout.buildDirectory.dir("rustJniLibs/release")
val uniffiOutDir = layout.buildDirectory.dir("generated/uniffi")

val ndkHome: String? =
    (project.findProperty("motang.ndkHome") as String?)
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK_ROOT")

val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * 解析 cargo 可执行文件。
 *
 * 为什么不能直接写 "cargo"：Gradle 守护进程的 PATH 往往不含
 * %USERPROFILE%\.cargo\bin（bootstrap 只改了当前 PowerShell 会话的 PATH）。
 * 结果就是「命令行能编译、Gradle 任务找不到 cargo」。
 * 优先级：Gradle 属性 > CARGO_HOME > 用户默认路径 > PATH。
 */
fun resolveCargo(): String {
    (project.findProperty("motang.cargo") as String?)?.let { return it }
    System.getenv("CARGO_HOME")?.let { cargoHome ->
        val exe = File(cargoHome, if (isWindows) "bin/cargo.exe" else "bin/cargo")
        if (exe.isFile) return exe.absolutePath
    }
    val userHome = System.getProperty("user.home")
    if (userHome != null) {
        val exe = File(userHome, if (isWindows) ".cargo/bin/cargo.exe" else ".cargo/bin/cargo")
        if (exe.isFile) return exe.absolutePath
    }
    return "cargo"
}

val cargoExe = resolveCargo()

fun cargoEnv(): Map<String, String> = buildMap {
    ndkHome?.let { put("ANDROID_NDK_HOME", it) }
    // cargo-ndk 需要 rustup 的工具链目录可被 cargo 找到；继承当前进程环境即可，
    // 仅在缺 CARGO_HOME 时补默认值，避免覆盖用户的 rustup 配置。
    if (System.getenv("CARGO_HOME") == null) {
        val userHome = System.getProperty("user.home")
        if (userHome != null) put("CARGO_HOME", File(userHome, ".cargo").absolutePath)
    }
}

val cargoBuildDebug by tasks.registering(Exec::class) {
    group = "rust"
    description = "cargo-ndk 构建 debug .so（arm64-v8a + x86_64，未 strip）"
    workingDir = rustProjectDir.asFile
    environment(cargoEnv())
    commandLine(
        buildList {
            add(cargoExe)
            add("ndk")
            add("-t"); add("arm64-v8a")
            add("-t"); add("x86_64")
            add("-o"); add(rustJniDebugDir.get().asFile.absolutePath)
            add("build")
            add("-p"); add("motang-ffi")
        },
    )
    // 增量：Rust 源码变化时才重编。
    inputs.dir(rustProjectDir.dir("motang-ffi/src"))
    inputs.dir(rustProjectDir.dir("motang-search/src"))
    inputs.file(rustProjectDir.file("Cargo.toml"))
    inputs.file(rustProjectDir.file("motang-ffi/Cargo.toml"))
    outputs.dir(rustJniDebugDir)
}

val cargoBuildRelease by tasks.registering(Exec::class) {
    group = "rust"
    description = "cargo-ndk 构建 release .so（仅 arm64-v8a）"
    workingDir = rustProjectDir.asFile
    environment(cargoEnv())
    commandLine(
        buildList {
            add(cargoExe)
            add("ndk")
            add("-t"); add("arm64-v8a")
            add("-o"); add(rustJniReleaseDir.get().asFile.absolutePath)
            add("build")
            add("--release")
            add("-p"); add("motang-ffi")
        },
    )
    inputs.dir(rustProjectDir.dir("motang-ffi/src"))
    inputs.dir(rustProjectDir.dir("motang-search/src"))
    inputs.file(rustProjectDir.file("Cargo.toml"))
    inputs.file(rustProjectDir.file("motang-ffi/Cargo.toml"))
    outputs.dir(rustJniReleaseDir)
}

val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "从 debug .so 生成 uniffi Kotlin 绑定"
    dependsOn(cargoBuildDebug)
    workingDir = rustProjectDir.asFile
    // debug 产物未 strip，元数据可读。用 x86_64（cargo-ndk 命名目录为 x86_64）。
    val soFile = rustJniDebugDir.get().asFile.resolve("x86_64/libmotang_ffi.so")
    doFirst {
        if (!soFile.isFile) {
            throw GradleException(
                "未找到 $soFile。请确认 cargo-ndk 已安装且 ANDROID_NDK_HOME 指向有效 NDK。",
            )
        }
    }
    commandLine(
        buildList {
            add(cargoExe)
            add("run")
            add("-p"); add("motang-ffi")
            add("--features"); add("bindgen-cli")
            add("--bin"); add("uniffi-bindgen")
            add("--")
            add("generate")
            add(soFile.absolutePath)
            add("--language"); add("kotlin")
            add("--out-dir"); add(uniffiOutDir.get().asFile.absolutePath)
            add("--no-format")
        },
    )
    inputs.file(soFile)
    inputs.file(rustProjectDir.file("motang-ffi/uniffi.toml"))
    outputs.dir(uniffiOutDir)
}

// 说明：不额外注册「创建输出目录」的任务——它会与 generateUniffiBindings 的
// outputs.dir 重叠，Gradle 会报 overlapping outputs。uniffi 自身会 create_dir_all。

// 签名口令来源：Gradle 属性 > 环境变量 > 仓库根 keystore.properties（已 gitignore）。
// 三者任一缺失则 storePath 为 null，走「无签名」分支并只告警。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

fun signingValue(key: String, envName: String): String? =
    (project.findProperty(key) as String?)
        ?: System.getenv(envName)
        ?: keystoreProps.getProperty(key)

android {
    namespace = "io.github.xht252.motang"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.xht252.motang"
        minSdk = 26
        targetSdk = 35
        versionCode = 10000
        versionName = "1.0.0"
    }

    // 自签直装：M0 起固定 release 签名，保证后续版本可覆盖安装。
    // 口令不落仓库。来源优先级：Gradle 属性 > 环境变量 > 仓库根 keystore.properties（gitignore）。
    signingConfigs {
        create("release") {
            val storePath = signingValue("MOTANG_STORE_FILE", "MOTANG_STORE_FILE")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("MOTANG_STORE_PASSWORD", "MOTANG_STORE_PASSWORD")
                keyAlias = signingValue("MOTANG_KEY_ALIAS", "MOTANG_KEY_ALIAS")
                keyPassword = signingValue("MOTANG_KEY_PASSWORD", "MOTANG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 配置了 release 密钥就沿用（避免换签名后无法覆盖安装）；
            // 未配置时保持 AGP 默认的 debug 签名，**不要**赋 null——那会导致
            // 产出 app-debug-unsigned.apk，无法安装。
            signingConfigs.getByName("release").takeIf { it.storeFile != null }?.let {
                signingConfig = it
            }
            // 模拟器需要 x86_64；真机 arm64。abiFilters 同时约束
            // rustJniLibs 与 JNA aar 内的 libjnidispatch.so。
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            // M0 关闭 R8：JNA + uniffi 绑定在 M0 尚未验证混淆安全性。
            // M6 再开启 full mode（proguard-rules.pro 已预置必要 keep 规则）。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    // ABI 策略：由 buildTypes.ndk.abiFilters 统一约束（同时作用于 rustJniLibs
    // 与 JNA aar 内的 libjnidispatch.so），不使用 splits。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir(uniffiOutDir)
        }
        getByName("debug") {
            jniLibs.srcDir(rustJniDebugDir)
        }
        getByName("release") {
            jniLibs.srcDir(rustJniReleaseDir)
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf("/META-INF/AL2.0", "/META-INF/LGPL2.1")
        }
    }
}

// 不声明 jvmToolchain：本机 JDK 由 JAVA_HOME 指向（bootstrap 负责设置）。
// 若声明 toolchain 21 而未配置 foojay resolver，在只装了 JDK 17 的机器上会
// 直接构建失败，对单人自建项目属于不必要的约束。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 签名缺失只告警，不阻断构建：M0 先保证管线可跑通。
// 但自签直装场景下「首次装机必须用 release 签名」，否则后续无法覆盖安装
// （Room 里的打卡与统计会随卸载丢失）。见 .kilo/memory/DECISIONS.md。
if (android.signingConfigs.getByName("release").storeFile == null) {
    logger.warn(
        "WARN: 未配置 MOTANG_STORE_FILE —— release APK 将无签名、无法安装。" +
            "请在 ~/.gradle/gradle.properties 或环境变量中提供签名四件套（见 keystore/README.md）。",
    )
}

dependencies {
    implementation(platform(libs.composeBom))
    implementation(libs.composeUi)
    implementation(libs.composeUiGraphics)
    implementation(libs.composeUiToolingPreview)
    implementation(libs.composeMaterial3)
    debugImplementation(libs.composeUiTooling)

    implementation(libs.material3Adaptive)
    implementation(libs.material3AdaptiveLayout)
    implementation(libs.material3AdaptiveNavigation)
    implementation(libs.androidxWindow)

    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxActivityCompose)
    implementation(libs.androidxLifecycleRuntimeKtx)
    implementation(libs.androidxLifecycleViewmodelCompose)
    implementation(libs.androidxNavigationCompose)

    implementation(libs.androidxRoomRuntime)
    implementation(libs.androidxRoomKtx)
    ksp(libs.androidxRoomCompiler)
    implementation(libs.androidxDatastorePreferences)

    // uniffi 0.32 生成的 Kotlin 绑定使用 JNA direct mapping，必须显式依赖。
    // 必须用 @aar：Maven Central 同时发布 jna.jar（无 Android native）与 jna.aar
    // （含 jni/<abi>/libjnidispatch.so）。默认会解析到 jar，运行期 JNA 找不到
    // jnidispatch 就会崩。@aar 是官方推荐的 Android 用法。
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
}

// ---------------------------------------------------------------------------
// 任务接线
// ---------------------------------------------------------------------------
// srcDir 只让 AGP 把生成目录当作源根，并不建立任务依赖；必须显式声明，
// 否则并行构建时 Kotlin 编译可能先于绑定生成开始，报 "Unresolved reference"。
//
// 注意：AGP 的 preBuild/preDebugBuild 等任务由 Variant API 在配置后段创建，
// 直接 tasks.named("preDebugBuild") 会在配置阶段抛
// "Task with name 'preDebugBuild' not found"。因此统一用 matching + configureEach，
// 任务出现时才挂依赖（对尚未创建的任务同样生效）。
tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }
    .configureEach { dependsOn(generateUniffiBindings) }
tasks.matching { it.name == "preReleaseBuild" }
    .configureEach { dependsOn(cargoBuildRelease) }
// Kotlin 编译与 KSP 同样需要绑定文件；按名字匹配，避免依赖 KGP 内部类名。
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }
    .configureEach { dependsOn(generateUniffiBindings) }
tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") }
    .configureEach { dependsOn(generateUniffiBindings) }
