# comsol-mcp 进化闭环协议

> 对应 autophotonicdesign 的 `program.md` loop 规约。对象是 **MCP 本身**:每次仿真任务自然就是一轮——撞到 MCP 的坑或缺能力就记一条,任务收尾或专门一轮扫 PENDING 改代码。**让 MCP 从一次次任务中进化。**

## 三件套

- `principles.md` — 稳定陷阱/约定知识库(低频变,开发前先读)
- `journal.md` — 进化账本,每条带状态 `[KEPT]`/`[PENDING]`/`[DISCARDED]`
- `results.tsv` — 一行一条改进的机械账本
- `tests/` + `verify.sh` — 回归门(见下"回归门")

## 回归门(verify.sh)— 棘轮的机器锁

每条 KEPT 配一个复现脚本(`tests/#N/run.sh`),`verify.sh` 遍历跑,FAIL=0 才算绿。

**两类测试:**
- **Type A(自动,comsol-batch 直跑)**:固定 helper 类(MaterialInfo/Inspect/ParamSweep…)。`run.sh` source `tests/_lib.sh`,调 `run_helper <Class> "prop=val"…`,grep 断言。不需 Claude/MCP server 在场,可挂 cron / pre-commit。第一轮已落 3 条:#2/#8/#9。
- **Type B(手动,build_model 路径)**:tryStmt/compile_diagnostics/mesh_audit 走动态 UserBuild*.java,没固定 helper。先放 `manual.md` 写手动复现步骤。目标:写 thin Python client 直调 `server.py`(把 mcp import 隔离),逐步 B→A。第一轮 3 条:#1/#7/#10。

**规则(加在闭环第 3 步之后):**
- 改 `server.py` / `java/*.java` 后,**必须 `./verify.sh` 全绿(FAIL=0)才能标 KEPT/commit**。
- 一条新 KEPT 必须同时提交 `tests/#N/run.sh` 复现脚本——**没复现脚本的"修好"不算 KEPT,标回 PENDING**。
- 改了 server.py 的 Python 端,先重启 MCP 再跑 verify(principles D1)。
- 快速门:`COMSOL_FAST=1 ./verify.sh` 跳过慢的 solve 测试(#9),给 pre-commit 用;完整门至少在 commit 前跑一次。
- 当前基线:`PASS=3 FAIL=0 MANUAL=3`(2026-07-13)。任何后续改动必须保持 FAIL=0,MANUAL 数应只减不增。

## 闭环(每次仿真任务即一轮)

### 1. 任务中撞坑 → 记 PENDING
任务里遇到 MCP 的 bug / 缺能力 / 报错隐晦 / 输出不符,先在 `journal.md` 加一条:
```
## #N — <一句话痛点>
- **来源**:任务名 / memory 链接
- **痛点**:...
- **修复方向**:... (哪怕只有方向也记)
- **状态**:`[PENDING]`
- **Lesson**:...
```
别等任务结束,撞到就记——这是"写在当时"原则(autoresearch 的核心教训)。

### 2. 任务收尾 → 扫 PENDING,挑高 ROI 的改
任务做完,`grep '\[PENDING\]' journal.md`,挑 1-3 条性价比最高的(影响多项目/复撞过/工作量小)改 MCP:
1. 先读 `principles.md` 对应小节,别重复踩已知陷阱。
2. 改 `server.py` / `java/*.java` / `build.sh`。
3. 编译:`bash build.sh`。
4. 端到端验证:用触发该痛点的真实模型跑一遍(别只 minimal model——#3 #9 的教训:同代码 minimal OK 复杂模型崩)。
5. 改 `principles.md`:新陷阱/约定补进对应小节(这是"稳定知识沉淀")。

### 3. 验证通过 → 标 KEPT + 推 GitHub
```
- **状态**:`[KEPT]` <日期>
```
`results.tsv` 加一行。commit + push(gh CLI 已配,见 [[visionpro-mcp-pushed-2026-07-11]]):
```
git add -A && git commit -m "mcp: #N <一句话>" && git push
```

### 4. 不修的 → 标 DISCARDED 附理由
工作量太大/低优先级/已有绕法 → 标 `[DISCARDED]` 写清为什么,避免下次重复评估。

## 规则(对应 autophotonicdesign 棘轮)

- **Read-before-write**:开发 MCP 前先读 `journal.md`(含 DISCARDED)+ `principles.md`,别重复踩坑/重复造轮子。
- **一条 commit 对应一条 journal**:保持因果清晰,`git commit -m "mcp: #N ..."` 引用 journal 编号。
- **失败也记**:DISCARDED 的 lesson 和 KEPT 一样有价值(#3→#9 就是 DISCARDED 复撞催生的)。
- **minimal model 不算验证**:#3/#9 教训——必须在触发该痛点的真实复杂模型上验证才算 KEPT。
- **Python 端改了提醒重启 MCP**:principles D1。
- **新陷阱进 principles**:凡是"下次还会踩"的写进 principles,凡是"一次性改进"的留 journal。

## 与 auto-memory 的接口

- `journal.md` = 仓库内全量明细(每条改进)。
- auto-memory `~/.claude/.../memory/mcp-optimization-2026-07-10.md` = 跨 session 蒸馏结论(backlog 指针)。
- 一个 phase 收尾(如"8 项全量完成")把定论蒸馏/更新进 memory,journal 留全量。新 session 开局:memory 给"还有哪些 PENDING",journal 给每条的细节。

## 触发口令

- 用户说"优化 MCP / 改进 comsol-mcp / tune the bridge / 修 MCP 的坑" → 走本协议:先 `grep '\[PENDING\]' journal.md`,按优先级实施。
- 任务中撞 MCP 坑 → 当场记一条 PENDING(哪怕这轮不修)。