# 文档索引（接手先读这一页）

> 目的：接手这个项目时，**先确定该信哪份文档**。本项目历史上出现过多次"文档说 A、代码是 B"，
> 甚至同一问题有三份口径不同的描述。本页给出每份文档的地位与读者，避免再被过时材料带偏。
>
> 更新日：2026-09-25 · 基线：HEAD `7f38d6b`、`mvn verify` **805 用例 + 4 IT 全绿**、main 164 类 / test 107 类。

## 1. 权威文档（以它们为准）

| 文档 | 回答什么问题 | 谁读 |
|---|---|---|
| `docs/requirements/SPEC.md` | **需求是什么**（113 条 GM/ET + 核心规则 + 安全设计） | 任何人；但**只看需求，不看它第 4 节的状态标注**（已作废，见下） |
| `docs/CODE-VS-SPEC.md` | **哪些已落地、落到位没有**（逐项实测 + 证据分级 A/B/C/D/E） | 排期、判断"能不能开工"时 |
| `docs/ONLINE-VERIFICATION.md` | **哪些只能在真机核销、怎么核销**（A1–A11） | 部署/上线前 |
| `docs/UNDECIDABLE-DECISIONS.md` | **哪些要人拍板**（B1–B5 商业参数、C1 多签语义、D/I 等） | 需要解锁阻塞项时 |
| `docs/superpowers/plans/2026-09-25-dev-plan.md` | **接下来怎么排**（本计划的唯一版本） | 每次开工前 |
| `docs/requirements/01`–`14` + `README.md` | **需求的溯源材料**（14 份原始投稿） | 只在需要追溯"某条为什么这么定"时 |

**先读顺序**：本页 → `CODE-VS-SPEC.md`（现状）→ `2026-09-25-dev-plan.md`（下一步）→ 按需查 `SPEC.md` / `ONLINE-VERIFICATION.md` / `UNDECIDABLE-DECISIONS.md`。

## 2. 已失效，不要引用

| 文档 | 为什么失效 |
|---|---|
| `docs/FEATURE-ROADMAP.md` | ① 用 **V2.0 的 G/T 编号**，与 SPEC 的 GM/ET **不是一套**（它自己开篇也标了版本警告）；② 基线是 193 测试；③ 它列"结构性缺口 S1/S2"与"可立刻开工波次"**均已完成**（S1 Bot 接线、S2 命令层、G-A/T-A/G-B 波次全部落地）。**保留仅作历史**。 |
| `docs/superpowers/plans/2026-09-24-full-dev-plan.md` | 已被**完全消耗**：它的 Wave 1b/1c/1d 列出的 17 个类（`TradeGroupLifecycle`/`VoterRegistry`/`VoteStake`/`MemberVote`/`FallbackSettle`/`VoteRewardAllocator`/`EvidenceChannel`/`EvidenceDeadline`/`AmountTierPolicy`/`NewcomerBadge`/`CounterpartyDiversityScorer`/`ChannelPuppetGuard`/`PhishingNotice`/`NoticePolicy`/`JoinOnboarding`/`AnomalyDetector` 等）**实测全部已存在**；它列的 Wave 2（S1）/Wave 3（持久化）也已完成。 |
| `SPEC.md` 第 4 节的状态列（✅/⬜） | 已加作废横幅：当时即漏标（ET-33/ET-34 标 ⬜ 而代码已落地），且口径把**零引用孤儿也算完成**。改用 `CODE-VS-SPEC.md`。 |
| `.rivet/backups/**` | 工具自动备份（含各文档的历史版本），**不是资产**，不引用。 |

## 3. 过程性文档（有用，但只在需要时看）

| 文档 | 用途 |
|---|---|
| `.rivet/HANDOFF.md` | 跨会话交接（**含每轮推进的坑与教训**，接手前值得读一遍） |
| `.rivet/plans/*.md` | 各次执行的实施计划。**10 份中 7 份已 EXECUTED**；未执行的 2 份：`mini-app-表单-s6-立项-实施计划`、`服务器部署与功能验证实施计划`（后者阻塞于服务器访问）；`v2-0-剩余功能续跑实施计划` 是伞形计划，**其基线 260 绿已过时** |
| `docs/superpowers/specs/*.md` | 设计 spec（对手方通知、服务器部署与验证） |
| `AGENTS.md` / `.rivet.md` | 本项目的 agent 工作纪律（高危命令、安全边界） |
| `README.md` / `DISCLAIMER.md` | 项目说明与免责 |
| `web/README.md` | Mini App 侧说明 |

## 4. 编号体系对照（最容易踩的坑）

| 体系 | 出处 | 状态 |
|---|---|---|
| **GM-xx / ET-xx** | `SPEC.md`（当前权威） | ✅ 用这个 |
| G1–G30 / T1–T58（**V2.0**） | `FEATURE-ROADMAP.md` | ❌ 已废 |
| T1–T75（**V3.0**，对 T 编号整体重排） | `docs/requirements/07-V3.0-开发文档.md` 第 4 节 | ⚠️ 仅溯源用；**引用编号前必查映射** |

> 例：V2.0 的 T38「交易阶段标注」在 V3.0 是 **T40**；而当前 SPEC 里它是 **ET-49**。
> 三套编号并存是历史遗留，**新文档一律用 GM/ET**。
