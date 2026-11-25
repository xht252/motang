plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}

// ---------------------------------------------------------------------------
// 墨唐 :llm 模块
// ---------------------------------------------------------------------------
// 职责：承载 llama.cpp（文本）与 mtmd（语音）的原生推理。
// 该模块的 Service 运行在 android:process=":llm"，避免推理 OOM 拖垮 UI 进程。
//
// 模型不入库（见 .gitignore 的 model/）；构建期只编译 llama.cpp 源码。
android {
    namespace = "io.github.xht252.motang.llm"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    // 只构建库，不构建 llama.cpp 的示例/工具/服务
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_CURL=OFF",
                    // 静态链接，最终只产出一个 libmotang_llm.so，避免多 so 的加载顺序问题
                    "-DBUILD_SHARED_LIBS=OFF",
                    // Android NDK 没有 OpenMP
                    "-DGGML_OPENMP=OFF",
                    // 关掉 ccache 探测噪音
                    "-DGGML_CCACHE=OFF",
                    // 16KB page size 对齐（NDK r27+ 默认已为 16KB，这里显式声明）
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
                cppFlags += listOf("-O3", "-fexceptions", "-frtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            // 模拟器需要 x86_64；真机 arm64
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinxCoroutinesAndroid)
}
