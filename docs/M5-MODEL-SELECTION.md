# M5 · 端侧模型选型（最终版，2026-11-25）

## 决策摘要

| 通道 | 模型 | 状态 |
|---|---|---|
| 文本解析 | **MiniCPM5-2B Q4_K_M**（openbmb/MiniCPM5-2B-gguf，Apache-2.0） | ✅ 已核实存在，1489 MB，纯文本 2B |
| 语音输入 | **Qwen3-ASR-0.6B Q8_0**（767.5 MB，Apache-2.0） | ✅ 已核实存在；Q8_0 而非 bf16：手机带宽瓶颈，体积减半、质量近无损 |
| 联网 | **不做** | 用户明确排除云端能力 |

**升级路径**：若确认 Qwen3.5 系列（0.6B/1.7B）已发布且提供 GGUF + llama.cpp 支持，
只需改 `model_catalog.json` 的 `id` / 下载源 / SHA256，代码零改动。
**本机当前无法访问 HuggingFace 与 GitHub raw（超时），故未核实 3.5 的存在与产物。**

## 为什么不是 omni

MiniCPM-o-4.5 ≈ 9B、Qwen2.5-Omni-7B，均远超手机端可行范围，且 llama.cpp 不支持其音频分支。
「语音」在端侧的正确做法是**独立 ASR 模型**，而不是 omni。

## 体积与性能预算

| 项 | 体积 | 备注 |
|---|---|---|
| Qwen3-1.7B Q4_K_M | ≈1.1 GB | T1 主用 |
| Qwen3-0.6B Q4_K_M | ≈0.4 GB | T0 兜底（低内存） |
| SenseVoice-Small int8 | ≈230 MB | 常驻可选，按需加载 |

峰值内存：1.7B Q4 ≈1.6–2.0 GB。因此在 `:llm` 独立进程 + 低内存降级到 0.6B。

## 吞吐优化（用户明确要求「尽可能高」）

1. `-DGGML_CPU_KLEIDIAI=ON`（Arm i8mm/dotprod 内核）
2. Q4_K_M 量化；不启用 CoT（token 数会翻倍）
3. 线程数 = 大核数；`mmap` 加载；KV cache 跨请求复用
4. 首 token 后立即流式回传，不等整段生成
5. 逐句解析按句提交，每句生成完即渲染

## 需要用户提供/确认

模型文件三选一：
1. **App 内下载**：需要 ModelScope 直链 + SHA256（我拿不到可访问的网络）
2. **手动放入**：把 GGUF 放到手机 `Download/`，App 提供「导入模型」入口
3. **由你下载后告诉我文件路径**，我写进 `model_catalog.json`

## 契约

逐句解析、缓存键、生命周期降级、验收指标沿用
`docs/M5-LLM-CONTRACT.md`；本文件只替换其第 1 节模型表与第 9 节下载项。
## 为什么不能用 ASR 模型替代对话模型

ASR（Qwen3-ASR-1.7B）的输出分布被约束在「音频对应的文字」，
没有指令微调与结构化输出对齐，无法执行「逐句注释/译文」这类生成任务。
两者必须分开：ASR 只做语音→文字，文字再交给 Qwen3.5-2B 解析。

## 内存策略

ASR 与 LLM **不同时驻留**：先跑 ASR，转写完成即释放，再加载 LLM。
峰值内存按单模型计算，避免 1.7B + 2B 叠加。
## 已确认产物（2026-11-25 核实 ModelScope）

| 文件 | 体积 | 用途 |
|---|---|---|
| `MiniCPM5-2B-Q4_K_M.gguf` | 1489 MB | 逐句解析 |
| `Qwen3-ASR-0.6B-Q8_0.gguf` | 767.5 MB | 语音转文字 |

放置位置：仓库根 `model/`（**已 gitignore**，2.2GB 不入库）。

## 集成方式

- llama.cpp 作为 **git submodule**（固定 tag），走标准 `externalNativeBuild.cmake`。
- 风险点：ASR 需要 llama.cpp 的**音频前端（mtmd）**支持，且 MiniCPM5 架构需被当前
  llama.cpp 版本识别。**先验证「加载 + 转写一句」，再写业务**。
## 实测结果（2026-11-25，host CPU）

| 项 | 结果 |
|---|---|
| llama.cpp b12726 编译 | ✅ MSVC x64 + AVX2 |
| 可执行名 | 该版本 `llama-cli` → **`llama-completion.exe`** |
| MiniCPM5-2B 加载 | ✅ 3.33 s |
| 生成吞吐 | ✅ **24.0 tok/s**（8 线程；prompt 165 tok/s） |
| 中文质量 | ✅ 正确识别《静夜思》并开始解释 |
| ASR 音频编码 | ✅ **89 ms** |
| ASR 转写 | ✅ `床前明月光，疑是地上霜。`（逐字正确） |

**关键结论**
- MiniCPM5-2B 默认输出 `<think>` 块 → **必须关闭 thinking** 才能保吞吐。
- `llama-mtmd-cli` **不支持 `--no-display-prompt`**（实测报错），脚本已修正。
- 测试音频可用 Windows SAPI（Microsoft Huihui）合成 16kHz 单声道，无需人工录制。
