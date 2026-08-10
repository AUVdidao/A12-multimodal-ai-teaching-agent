# PPT Visual QA Gating V1

## Boundary

PPT QA is intentionally split into two layers:

- **Geometry QA** is deterministic and blocking. Runner geometry failures, preview manifest mismatches, PNG signature/size/dimension/SHA-256 failures, and uncontrolled preview references fail the artifact gate.
- **AI Visual QA** reads the real rendered PNG previews and reports visible issues, severity, confidence, description, and repair hint. It is probabilistic and does not replace deterministic QA.

比赛可用口径：**系统支持基于渲染结果的 AI 视觉质量审核，并将模型视觉建议与确定性质量门禁分离。**

这不等于 AI 已能可靠自动判定所有 PPT 视觉错误。

## Review modes

`PPT_HARNESS_VISUAL_REVIEW_MODE` accepts:

- `DISABLED`: 不调用 Vision Provider。报告记录 `state=NOT_RUN`；Geometry 通过即可交付。
- `ADVISORY`（默认）: 调用 Vision Provider，保留全部 Visual findings。Visual `ERROR`、timeout、5xx、缺少 Vision model、以及一次结构化 repair 后仍非法，都记录为视觉评估失败或不可用，但不能单独阻止 Geometry 已通过的 Artifact。
- `ENFORCING`: 显式 opt-in。Visual `ERROR` 或 Visual review 无法完成时 fail closed；Geometry 仍然优先且独立。

旧 `PPT_HARNESS_VISUAL_REVIEW_ENABLED` 只用于兼容：新 mode 存在时优先使用；没有新 mode 时，旧 `false` 映射 `DISABLED`，旧 `true` 映射 `ADVISORY`。没有任一配置时默认 `ADVISORY`。ENFORCING 必须由调用方显式设置，不能由旧布尔值触发。

## Gate decision

QA report 保留明确的独立语义：

```json
{
  "artifactGatePassed": true,
  "visualReview": {
    "mode": "ADVISORY",
    "state": "SUCCEEDED",
    "reviewedSlideCount": 9,
    "issueCounts": { "total": 1, "info": 0, "warning": 0, "error": 1 },
    "visualAssessmentPassed": false,
    "issues": [
      { "slideNumber": 4, "code": "ELEMENT_OVERLAP", "severity": "ERROR", "confidence": 0.91,
        "description": "Two visible elements overlap", "repairHint": "Separate the affected elements" }
    ]
  }
}
```

`visualAssessmentPassed` 只表达 AI 对已审查 PNG 的判断；`artifactGatePassed` 才表达最终 Artifact 是否允许交付。ADVISORY 可以出现 `visualAssessmentPassed=false` 而 `artifactGatePassed=true`。DISABLED 使用 `visualAssessmentPassed=null` 与 `state=NOT_RUN`，不伪造视觉成功。

Provider failure 会保留安全的 `failure.code/message`，状态为 `UNAVAILABLE` 或 `FAILED_TO_REVIEW`，不会吞掉失败，也不会把失败伪造成 clean。

## Safety invariants

Canonical Visual QA 继续使用真实 PNG preview、Manifest 页序、controlled `downloadRef`、PNG 签名/尺寸/SHA-256 校验、显式 `KIMI_VISION_ENABLED` gate、独立 `KIMI_VISION_MODEL`、`thinking: disabled`、strict `json_schema`、AbortController timeout、安全 provider error，以及最多一次结构化 repair。没有 Candidate A/B prompt、confidence threshold production policy、localized repair、SlideSpec/template/Runner 生成逻辑改动或自动修 PPT。

## Calibration basis

当前 PPT-012 calibration baseline 为 20 cases：Precision `0.1622`、Recall `0.9231`、FPR `0.8571`、Clean Pass `0.1429`。这表明召回较高但 raw precision 很低，不能把模型视觉判断默认当作硬阻断。PPT-013 的 precision recovery experiment 中 Candidate A/B 没有带来严格的 clean hard-fail 改善，因此本版本保持 Advisory-by-default，等待可靠的新校准证据后再考虑显式 Enforcing。
