# M5 · 端侧 LLM 接口契约与选型

> 本文件是 M5 开工前的 Architect 产出（按 `.kilo/agent/architect.md` 要求）。
> 实现必须严格按此契约，变更需同步更新本文件与 DECISIONS。

## 1. 模型选型（约束：≤1.5B，开源 SOTA 优先）

| 档位 | 模型 | 参数量 | 量化 | 体积 | 说明 |
|---|---|---|---|---|---|
| T1 主用 | **Qwen2.5-1.5B-Instruct** | 1.54B | Q4_K_M | ≈1.0 GB | 目前同量级中文能力最稳；指令跟随好，适合「按三节输出」的约束 |
| T0 兜底 | **Qwen3-0.6B** | 0.6B | Q4_K_M | ≈0.4 GB | 低内存设备；可用但文采弱，需更强约束 |
| — | ~~Qwen3-1.7B~~ | 1.7B | — | — | **超出 1.5B 约束，不采用** |

备选（同档可替换，目录驱动即可切换）：
- `gemma-3-1b-it`（Q4_K_M）：多语言好，中文古文偏弱
- `Llama-3.2-1B-Instruct`：中文弱于 Qwen
- `DeepSeek-R1-Distill-Qwen-1.5B`：推理强但输出冗长，不利于三节短答

**决策**：T1 = Qwen2.5-1.5B-Instruct，T0 = Qwen3-0.6B。
档位判定沿用 DECISIONS 第 21 条：`SOC_MODEL` + `MemTotal` + 首跑 64-token 微基准；
**模型由 `model_catalog.json` 数据驱动，禁止代码写死机型**。

## 2. 模块边界

```
:llm 模块（android:process=":llm"）
├─ LlmService        绑定式 Service，持有 llama.cpp 上下文
├─ ModelManager      workManager 下载 / SHA256 校验 / 断点续传 / 删除
├─ PromptBuilder     prompt_ver 版本化的模板
└─ AIDL 或 Messenger 与主进程通信（流式回传 token）
```

主进程只做：发起请求、渲染流式文本、读写解释缓存（Room）。

## 3. 数据契约

```kotlin
data class ExplainRequest(
  val poemId: String,
  val title: String,
  val author: String,
  val lines: List<String>,   // 逐句解析：按句返回
  val promptVer: Int = 1,
)

sealed interface ExplainEvent {
  data class Token(val text: String) : ExplainEvent
  data class LineDone(val index: Int, val note: String) : ExplainEvent
  data object Done : ExplainEvent
  data class Failed(val reason: String) : ExplainEvent
}
```

**逐句解析**是 M4 已预留的 `LineAnalysis.note`：LLM 按句输出，
每句一段，写入 `note`。缓存键：

```
sha256(poem_id + prompt_ver + model_id)
```

## 4. Prompt（v1）

```
system: 你是古典文学助教。仅依据给定诗文作答。
        对每一句给出：字词注释 + 白话译文，合并为一段，不超过 60 字。
        不得虚构典故出处，不确定时写「存疑」。
user: 《{title}》{author}
      {逐句，每句一行，编号}
      请逐句解释，按编号输出，每句一行。
```

参数：`ctx=2048`、`max_tokens=512`、`temp=0.3`、`top_p=0.9`、线程数=大核数。

## 5. 缓存表（Room）

```sql
CREATE TABLE explanation (
  cache_key TEXT PRIMARY KEY,   -- sha256(poem_id+prompt_ver+model_id)
  poem_id   TEXT NOT NULL,
  model_id  TEXT NOT NULL,
  prompt_ver INTEGER NOT NULL,
  content   TEXT NOT NULL,      -- JSON: 每句 note
  created_at INTEGER NOT NULL
);
```

命中缓存 → 零等待；「按新模型重新生成」按钮用新 `model_id` 自然失效。

## 6. 生命周期与降级

| 场景 | 行为 |
|---|---|
| 未下载模型 | 解析区显示「下载模型」入口 + 体积 + 来源 |
| 下载中断 | WorkManager 续传，SHA256 不匹配则删除重下 |
| `:llm` 进程被杀 | 主进程收到 binder 死亡 → 提示「重试」，不影响其他功能 |
| 内存不足 | T0 兜底；启动前检查 `MemTotal` |
| 过热 | `thermalHeadroom` 低于阈值 → 降线程 + 提示 |
| 旋转/开合 | 流式缓冲在 ViewModel，Activity 重建后继续渲染 |

## 7. 合规

- 所有解释固定标注「AI 生成，仅供参考」
- 不得虚构典故；不确定标「存疑」
- 模型不随 APK 分发，用户显式下载

## 8. 验收指标

| 项 | 目标 |
|---|---|
| T1 首 token | ≤ 2 s |
| T1 吞吐 | ≥ 15 tok/s |
| T0 首 token | ≤ 4 s |
| 缓存命中 | 二次进入 0 等待 |
| 推理期 UI jank | < 1% |
| 飞行模式 | 已缓存解释可读 |

## 9. 需要外部下载（由用户执行）

1. **llama.cpp 源码**：`https://github.com/ggml-org/llama.cpp`（git submodule 到 `llm/llama.cpp`，固定 tag）
2. **模型 GGUF**：
   - `Qwen2.5-1.5B-Instruct-Q4_K_M.gguf`（≈1.0 GB）
   - `Qwen3-0.6B-Q4_K_M.gguf`（≈0.4 GB）
   - 首选 ModelScope 国内源，校验 SHA256 后放入手机（App 内下载）

> 未经你确认前，不会自动改写 Gradle 引入原生构建。
