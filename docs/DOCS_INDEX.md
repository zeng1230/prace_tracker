# Price Tracker 文档索引

本页只索引当前 Git 提交中实际受版本控制的文档。`docs/` 下仍可见的其他文件可能是 2026-07-08 文档清理后遗留在本机的 ignored 文件，不应作为跨环境的仓库事实。

## 当前状态入口

| 文档 | 用途 |
| --- | --- |
| [README](../README.md) | 项目定位、实现能力、运行方式和能力边界 |
| [仓库事实审计](PROJECT_STATE_AUDIT.md) | 以当前代码、Git、测试和配置为依据的真实阶段、能力、偏差与路线 |
| [下一会话上下文](CODEX_NEXT_CONTEXT.md) | 下一次开发开始前应读取的最小事实快照 |
| [API 契约](API_CONTRACT.md) | 当前 HTTP API、权限和异步副作用 |
| [发布候选验收](RELEASE_CANDIDATE_ACCEPTANCE.md) | 尚需执行并记录的手工 E2E 与类生产验收步骤 |
| [性能基线](PERFORMANCE_BASELINE.md) | 待实测的性能与容量项目；当前不包含实际生产基线 |
| [仓库协作规则](AGENTS.md) | 项目边界、成熟度限制与工程约束 |

## 历史材料处理规则

- `STAGE5_DELIVERY.md`、`Stage5Plan.md`、`STAGE_HANDOFF.md`、`RESUME_DELIVERY.md` 和旧 `CODEX_CONTEXT.md` 属于过期阶段材料；它们已在提交 `ecad16e` 中从 Git 删除，本机残留也被 `.gitignore` 忽略。
- 若未来确需保留这些材料，应先校验内容，再以日期和主题命名后放入 `docs/archive/legacy-stage-naming/`；不要恢复为“当前状态”入口。
- `docs/prompt/` 与 `docs/superpowers/plans|specs/` 记录的是提示词、计划或设计，不等于已实现能力。

## 推荐阅读顺序

1. [仓库事实审计](PROJECT_STATE_AUDIT.md)
2. [README](../README.md)
3. [API 契约](API_CONTRACT.md)
4. [发布候选验收](RELEASE_CANDIDATE_ACCEPTANCE.md)
5. [下一会话上下文](CODEX_NEXT_CONTEXT.md)
