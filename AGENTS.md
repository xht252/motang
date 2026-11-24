# 墨唐 MoTang（motang）

唐诗背诵 App。Kotlin + Jetpack Compose（UI）+ Rust（检索核心/ETL，uniffi 绑定）+
端侧 LLM（llama.cpp，M5 引入）。自签名 APK 直装，不上架 Google Play。

## 快速入口

| 内容 | 位置 |
|---|---|
| 硬性约束（必读） | `.kilo/rules/project.md` |
| 架构决策与事实纠偏 | `.kilo/memory/DECISIONS.md` |
| 里程碑状态 / 版本矩阵 / 接口契约 | `.kilo/memory/MILESTONES.md` |
| 九个专项 Skill | `.kilo/skills/*/SKILL.md` |
| 子代理（架构 / 排障） | `.kilo/agent/*.md` |
| 工具链引导 | `.kilo/scripts/bootstrap.ps1` |
| M0 验收 | `.kilo/scripts/verify-m0.ps1` |
| 16KB 对齐校验 | `.kilo/scripts/check-16k-pagesize.ps1` |

## 首次构建

```powershell
# 1. 安装工具链（需你执行；Agent 不代跑）
powershell -ExecutionPolicy Bypass -File .kilo/scripts/bootstrap.ps1

# 2. 构建
.\gradlew.bat :app:assembleDebug

# 3. 验收
powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-m0.ps1
```

## 当前环境注意

- **compileSdk / targetSdk = 35**（本机仅有 platform-35；升 36 的清单见 DECISIONS 第 2 条）。
- 本机原无 Rust / Gradle / NDK / cmdline-tools，需先跑 bootstrap。
- 目前只能**模拟器**验证；真机项（OriginOS、折叠悬停、T1/T2 档位）为外部依赖。

## 目录

```
app/                     Compose UI + Kotlin 业务（单 Activity）
core-rs/                 Rust workspace
  motang-etl/            离线语料 ETL（bin）
  motang-search/         检索核心（lib）
  motang-ffi/            uniffi 绑定层（cdylib + bindgen bin）
.kilo/                   rules / skills / agent / memory / scripts
```
