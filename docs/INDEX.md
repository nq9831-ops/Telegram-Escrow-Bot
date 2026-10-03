# 文档索引（接手先读这一页）

> 目的：接手这个项目时，**先确定该信哪份文档**。本项目历史上出现过多次"文档说 A、代码是 B"，
> 甚至同一问题有三份口径不同的描述。本页给出每份文档的地位与读者，避免再被过时材料带偏。
>
> 更新日：2026-10-03 · 基线：`mvn clean verify` 全量绿（数字见 `CODE-VS-SPEC.md` §4——本页不再重复抄录，
> 以 CODE-VS-SPEC 为唯一数字源）。本页快照（2026-09-29 时点：877 用例 + 4 IT、main 174 类）**已过时，勿引用**。
> ⚠️ **本页数字会过时**——它只是上一次重算时的快照。引用前请按 `CODE-VS-SPEC.md` §5 的命令复算；
> 同样地，`CODE-VS-SPEC.md` 的 §2 逐项表已于 **2026-09-29** 按「引用 + 注解」机械复核（见该文 §6.1）；其 C 级「缺件」描述仍未逐行回读。

## 1. 权威文档（以它们为准）

| 文档 | 回答什么问题 | 谁读 |
|---|---|---|
| `docs/QUICKSTART.md` | **怎么跑起来**（构建 / 测试 / 启动的最短路径 + 模块地图；命令为实测） | 刚拿到仓库、要动手的人——先读它 |
| `docs/requirements/SPEC.md` | **需求是什么**（113 条 GM/ET + 核心规则 + 安全设计） | 任何人；但**只看需求，不看它第 4 节的状态标注**（已作废，见下） |
| `docs/CONFIG-KEYS.md` | **配置键权威清单**（67 键，命令实测生成——部署者配什么、默认值、配错会怎样） | 配置/部署前 |
| `docs/CODE-VS-SPEC.md` | **哪些已落地、落到位没有**（逐项实测 + 证据分级 A/B/C/D/E） | 排期、判断"能不能开工"时 |
| `docs/ONLINE-VERIFICATION.md` | **哪些只能在真机核销、怎么核销**（A1–A11） | 部署/上线前 |
| `docs/UNDECIDABLE-DECISIONS.md` | **哪些要人拍板**（B1–B5 商业参数、C1 多签语义、D/I 等） | 需要解锁阻塞项时 |
| `docs/archive/plans/2026-09-25-dev-plan.md` | **接下来怎么排**（该计划的历史版本，已归档——2026-10-03 起新排期见 `.rivet/plans/` 当前活动计划或 HANDOFF） | 需要历史排期时 |
| `docs/requirements/01`–`14` + `README.md` | **需求的溯源材料**（14 份原始投稿） | 只在需要追溯"某条为什么这么定"时 |

**先读顺序**：本页 → `docs/QUICKSTART.md`（要动手时）→ `CODE-VS-SPEC.md`（现状）→ 按需查 `SPEC.md` / `ONLINE-VERIFICATION.md` / `UNDECIDABLE-DECISIONS.md`。
> 注：早期顺序里的 `.rivet/HANDOFF.md` 是 **gitignore 的本地交接文件、不入版本库**——git clone 的读者拿不到它；只有本机内部会话续接工作时才读。

## 2. 已失效，不要引用

| 文档 | 为什么失效 |
|---|---|
| `docs/FEATURE-ROADMAP.md` | ① 用 **V2.0 的 G/T 编号**，与 SPEC 的 GM/ET **不是一套**（它自己开篇也标了版本警告）；② 基线是 193 测试；③ 它列"结构性缺口 S1/S2"与"可立刻开工波次"**均已完成**（S1 Bot 接线、S2 命令层、G-A/T-A/G-B 波次全部落地）。**保留仅作历史**。→ **已于 2026-09-25 删除**。取回：`git log --diff-filter=D -- docs/FEATURE-ROADMAP.md` 找删除提交，再 `git show <sha>^:docs/FEATURE-ROADMAP.md`。 |
| `docs/superpowers/plans/2026-09-24-full-dev-plan.md` | 已被**完全消耗**：它的 Wave 1b/1c/1d 列出的 17 个类（`TradeGroupLifecycle`/`VoterRegistry`/`VoteStake`/`MemberVote`/`FallbackSettle`/`VoteRewardAllocator`/`EvidenceChannel`/`EvidenceDeadline`/`AmountTierPolicy`/`NewcomerBadge`/`CounterpartyDiversityScorer`/`ChannelPuppetGuard`/`PhishingNotice`/`NoticePolicy`/`JoinOnboarding`/`AnomalyDetector` 等）**实测全部已存在**；它列的 Wave 2（S1）/Wave 3（持久化）也已完成。→ **已于 2026-09-25 删除**。取回：`git log --diff-filter=D -- docs/superpowers/plans/2026-09-24-full-dev-plan.md` 找删除提交，再 `git show <sha>^:<该路径>`。 |
| `SPEC.md` 第 4 节的状态列（✅/⬜） | 已加作废横幅：当时即漏标（ET-33/ET-34 标 ⬜ 而代码已落地），且口径把**零引用孤儿也算完成**。改用 `CODE-VS-SPEC.md`。 |
| `.rivet/backups/**` | 工具自动备份（含各文档的历史版本），**不是资产**，不引用。 |

## 3. 过程性文档（有用，但只在需要时看）

| 文档 | 用途 |
|---|---|
| `docs/TON-DOCS-DIGEST.md` | **TON 官方文档学习纪要**（docs.ton.org 速查 + 与本项目代码对照 + "别踩"清单；含官方文档抓取方法） |
| `.rivet/HANDOFF.md` | 跨会话交接（**含每轮推进的坑与教训**）——**本地文件、不入版本库**，仅本机内部会话可读 |
| `docs/archive/plans/*.md` | 各次已执行计划的归档（2026-10-03 起；原 `.rivet/plans/` 只保留当前活动计划）。计划里记录的基线数字（如某伞形计划写的"260 绿"）**尤其不要引用**，那是当时状态 |
| `docs/archive/plans/`（原 `docs/superpowers/specs/`） | 设计 spec（对手方通知、服务器部署与验证），已归档 |
| `AGENTS.md` / `.rivet.md` | 本项目的 agent 工作纪律（高危命令、安全边界） |
| `README.md` / `DISCLAIMER.md` | 项目说明与免责 |
| `web/README.md` | Mini App 侧说明 |

## 4. 编号体系对照（最容易踩的坑）

| 体系 | 出处 | 状态 |
|---|---|---|
| **GM-xx / ET-xx** | `SPEC.md`（当前权威） | ✅ 用这个 |
| G1–G30 / T1–T58（**V2.0**） | `FEATURE-ROADMAP.md` | ❌ 已废 |
| T1–T75（**V3.0**，对 T 编号整体重排） | `docs/requirements/archive/07-V3.0-开发文档.md` 第 4 节 | ⚠️ 仅溯源用；**引用编号前必查映射** |

> 例：V2.0 的 T38「交易阶段标注」在 V3.0 是 **T40**；而当前 SPEC 里它是 **ET-49**。
> 三套编号并存是历史遗留，**新文档一律用 GM/ET**。
