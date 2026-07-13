---
name: comsol-3d-ewfd
description: 3D frequency-domain electromagnetic (ewfd) periodic-structure / MIM-microcavity simulation in COMSOL 6.4 driven headless via the comsol-mcp tools — geometry (parameterized L-patch / unit cell), materials with COMSOL e^{iωt} sign convention, IdenticalMesh ordering, Floquet periodic ports + oblique AOI, iterate-mode parametric sweeps, reflection-spectrum extraction (R=|S11|²), and native 3D slice/structure rendering. Crystallizes the Proscia 2023 MIM L-cavity mid-IR vibrational-strong-coupling reproduction (arXiv 2303.04133v2). Built for an optics/physics researcher doing COMSOL microcavity / polariton / MIM / metasurface work; figures and extraction follow the Proscia project conventions.
triggers:
  # build / model
  - "3d 仿真|3d 仿真|3d ewfd|3d comsol|建 3d|3d 建模|建3d"
  - "MIM 腔|MIM 微腔|mim cavity|L 腔|L-cavity|L patch|L 形"
  - "周期结构|periodic structure|metasurface|超表面|unit cell|晶胞"
  - "floquet|周期 port|周期端口|oblique|AOI|斜入射"
  # sweep / extract
  - "参数扫|parametric sweep|扫 L|Lx Ly 扫|iterate 模式"
  - "反射谱|R of omega|R(ω)|反射率|S11|reflection spectrum"
  - "极化子|polariton|Rabi|强耦合|strong coupling"
  # render
  - "3d 电场图|3d 结构图|原生 3d 图|slice plot|切面图|export_slice_3d"
  # general
  - "comsol 3d|headless comsol 3d|打通 3d|3d 仿真这条路"
---

你是光学/物理研究者的 COMSOL 3D 频域电磁仿真副驾。这个 skill 把 Proscia 2023 MIM L-cavity 中红外振动强耦合复现里**打通 3D 仿真这条路**踩出的全部方法、陷阱与决策固化成一条可复用的流水线，并通过 `comsol_*` MCP 工具执行硬操作。

**素材库（绝对路径引用，不复制内容）：**
- 陷阱库（开发/使用 MCP 前必读）：`/home/pengyk/Project/.comsol-mcp/principles.md`
- 进化账本（KEPT/PENDING + 复现脚本）：`/home/pengyk/Project/.comsol-mcp/journal.md` + `results.tsv`
- 真实复现产物（最权威参照）：`/home/pengyk/Project/Proscia2023_MIM_Lcavity/`
  - 报告：`02_results/REPORT.md`
  - 建模+扫描 body：`03_scripts/SweepLxLy.java.body`
  - 数据/图：`02_results/data/`、`02_results/figures/`
  - 模型：`01_models/mim_v38.mph`（已解 3D，含 dset1）

## 铁律（每次都生效）

1. **先读 principles.md。** 3D ewfd 的大坑（材料 identifier、符号约定、IdenticalMesh 顺序、batch result 上下文几何封禁）全在里面，不读必重踩。见下文「核心陷阱速查」。
2. **COMSOL 用 e^{iωt} 约定。** 无源损耗材料 ⟹ Im(ε)≤0；Drude/Lorentz 振子的 i 项**取共轭**，否则 Im(ε)>0 变增益、R>1（物理不可能）。用 `add_dispersion_material` 时该工具内置符号已修（journal #11 KEPT，自检 Im(ε)≤0）。
3. **几何参数化、域编号稳定。** 用 `model.param().set` 驱动 Lx/Ly/L/P，`g.run()` 重建几何。**Form Union 后域编号在拓扑不变下恒定**（Proscia 恒 7 域），材料/面选择无需重新派生——这是参数化扫能一条 JVM 跑完的前提。新建几何别手填域号，先 dump 一次确认。
4. **IdenticalMesh 必须在 FreeTet 之前。** 网格特征顺序 `size, size1, sLp, id1, id2, ftet`；用 `mesh.feature().move("ftet", lastIndex)` 重排。顺序错 → Floquet "source and destination meshes not compatible"，且**不报错只算错**。id1/id2 按层 z 范围+侧别配对周期面（同域配对）。
5. **参数扫用 iterate 模式。** ewfd 模型已有 Frequency sweep 时再叠 Parametric feature 报 `Invalid_property_value`。用 `param_sweep(mode="iterate")`：循环 `model.param().set + study.run()`，一个 JVM 跑 N 值，`g.run()`+`mesh.run()` 自动重建。
6. **3D 出图走 Slice plot feature，不要碰 CutPlane dataset。** headless batch 的 `model.result()` 上下文**封禁一切几何操作**（见陷阱 C2）；Slice 把切面定义在 plot feature 内部，渲染是 eval 操作，batch 允许。用 `export_slice_3d` 工具。
7. **图像渲染用软件渲染器。** `export_image`/`export_slice_3d` 固定 `-3drend sw`；ogl 在 headless batch 必崩在 26%。
8. **图像一律过 visionpro MCP 验证。** 出图后用 `mcp__visionpro__describe_image` 读回确认内容（颜色条/标题/L 形可见），不凭文件大小猜结果。这是项目级强制规则。

## 流水线（7 阶段）

### 阶段 1 — 清理与定位
- 旧仿真常错配论文。先核对 PDF 与现有 .mph 是否同一物理（Proscia 是 3D MIM L-cavity 振动强耦合，不是 Si qBIC dimer）。错配则从零重做，不缝补。
- 目录命名要去误导性（`金属 MIM 结构/` → `Proscia2023_MIM_Lcavity/`）。

### 阶段 2 — Setup 验证（建几何前必做）
- `nvidia-smi`（GPU 可见性，但 3D ewfd GPU 标志常被拒，预期回退 CPU PARDISO 8 线程）。
- `ls /home/pengyk/comsol64/bin/comsol` + Wave Optics 许可。
- 建 1 µm³ 真空 + 1 Port + 1 freq sanity 解，确认 `comsol batch` 能起、求解器非 MUMPS-only。
- **清孤儿 COMSOL 进程**：headless batch 残留的 `comsol`/`java` 进程会竞争 license 与 CPU 使单解从 ~630s 飙到 1274s。求解变慢先 `pkill -f comsol` 再排查。

### 阶段 3 — 几何（参数化 3D 单胞）
- `geom().create("geom1", 3)` + Block 分层（Si 衬底 / 底 Au / 间隔层 / 空气+patch）。
- L-patch：WorkPlane at patch 顶面 z → 两个 Polygon 拼接（臂 X 沿 x、臂 Y 沿 y）→ Union → Fillet（外角 50 nm、臂尖 150 nm ROC；Fillet flaky 时退化为尖角，误差 <2%）→ Extrude 向下 → Difference 从 air 块切出。
- **参数全部注册到 `model.param()`**：P/w/hAuB/hIPD/hL/zTop/AOI/wn0/wnMin/wnMax/Lx/Ly。扫 Lx/Ly 只改这两个。
- **Form Union 后 `dump_model` 确认域编号**，再写材料/面选择，避免手填错域号。
- 参照：`03_scripts/SweepLxLy.java.body`。

### 阶段 4 — 材料（COMSOL e^{iωt} 约定）
- Au（mid-IR）：Drude+Lorentz 或实测 ε 表。用 `add_dispersion_material(kind="lorentz+drude", ...)`。
- 振子材料（IP-Dip / 钙钛矿等）：`add_dispersion_material(kind="lorentz", ...)`，振子频率/阻尼用**带单位的 Hz**（如 `1[THz]`），epsinf/S 无量纲。
- **符号**：该工具已按 e^{iωt} 修正（Lorentz 分母 `+i·wγ`、Drude `-i·wγD`），并写 `<<<SANITY>>>` 采样要求 Im(ε)≤0。增益材料会大声失败。
- 常数材料（Si/air）直接 `relpermittivity = n²`。
- **别用 `propertyGroup().create(tag,"RefractiveIndex")`** 手建——identifier=tag ≠ 参考材料的 "rfi"，ewfd 找不到 n 报 "Undefined material property n"。用 `material.duplicate()` 继承 identifier，或 `material_info` 先查。

### 阶段 5 — 物理（3D ewfd + Floquet + 周期 port + PML）
- `physics().create("ewfd","ElectromagneticWaves","geom1")`，EquationForm=Frequency，BackgroundField SolveFor=fullField。
- **周期面**（±x、±y 4 个侧面）用 `PeriodicCondition` + `PeriodicType="FloquetBloch"`，`kvector={(2πf/c)sin(AOI), 0, (2πf/c)(-cos(AOI))}`。
- **顶面周期 port**：`Port` + `PortType="Periodic"`，`kdir={sin(AOI),0,-cos(AOI)}`，`E0i` 定极化：s-pol `{0,1,0}` / p-pol `{0,0,1}` / 45° `{0,0.707,0.707}`。
- **PML** 在 Si 衬底下（Cartesian、z 向、Polynomial、factor 2、8 层）。
- **R(ω) 提取**：`R = abs(ewfd.S11)^2`，用 `eval_aggregate` 或 `get_result(["abs(ewfd.S11)^2"])`。
- 面选择编号从 `dump_model`/`probe_feature` 读，别猜。

### 阶段 6 — 网格 + 求解 + 扫描
- 网格特征顺序**必须** `size, size1, sLp, id1, id2, ftet`（IdenticalMesh 在 FreeTet 之前，见铁律 4）。配对周期面按层 z 范围+侧别同域配对。L-patch 用细 size（~25 nm），薄层 size1，远处默认。
- 求解器 PARDISO 直接解；GPU 标志在 3D ewfd 常被拒，回退 CPU PARDISO 8 线程（Plan #0 fallback）。
- 扫描用 `param_sweep(mode="iterate")`（铁律 5），**不要叠 Parametric feature**。
- 分层跑：先 1 cavity × 3 极化 sanity（看 R 谱有 dip、dip 数对、位置 ~目标）→ 再 5×5 L 扫 × 1 极化（主结果）。允许过夜。
- **可中断/可恢复**：每腔解完立即 flush CSV，下次跳过已存在 CSV（见 SweepLxLy.java.body 的 `f.exists() && f.length()>500` 守卫）。

### 阶段 7 — 后处理 + 原生 3D 出图
- 反射谱：每 (Lx,Ly,极化) 输出 CSV `R_<tag>_pol<phi>.csv`（wn_cm1,R,Atotal）。
- 极化子三峰拟合：MATLAB `FitPolaritons.m`（3-Lorentzian），出 L/M/U 中心、Q、Rabi 分裂。
- 图：MATLAB 优先（散点热图 delaunay+patch），不用 Python。
- **原生 3D 图用 `export_slice_3d`**（铁律 6/7）：
  - 结构图 `expr="dom"`，切到 patch 中高 z。
  - 场图 `expr="ewfd.normE"` + `logscale=True`，切到间隔层 z，`solnum` 选目标 freq 的解索引。
  - 切片定位：`quickplane`("xy"/"xz"/"yz")+ `quickz`(坐标，model 长度单位)+ `quickn`(切片数，**必须≥1**，不设默认 0 → 只剩线框)。
  - 出图后**用 visionpro 验证**（铁律 8）。

## 核心陷阱速查（principles.md 摘要）

| 代号 | 陷阱 | 正路 |
|---|---|---|
| A1 | material propertyGroup identifier=tag≠"rfi" → "Undefined n" | `material.duplicate()` 继承 identifier；`material_info` 先查 |
| A2 | `.geom(dim)` 是 setter 不是 getter；selection 写前必须 `.geom(geom,dim).set(int[])` | 写用 `BridgeUtil.meshSelect`；读用 `.selection().entities()` 直接读 |
| A3 | 符号约定错 → Im(ε)>0 增益 R>1 | e^{iωt} 取共轭；`add_dispersion_material` 已修 + 自检 |
| B1 | 1D out-of-plane ewfd 看不到面内方向谐振 | 2D/3D 周期结构用 `geom(,2/3)` + 对应 ewfd |
| B2 | Parametric 叠 Frequency sweep → Invalid_property_value | `param_sweep(mode="iterate")` |
| B3 | eval_aggregate max 在角点奇异给虚假增强 | integral 或多点交叉验证 |
| C1 | ogl 在 headless batch 崩在 26% | `comsol batch -3drend sw` 软件渲染 |
| C2 | batch result 上下文封禁几何操作（CutPlane dataset、plot feature selection、Box/Adjacent 选择、自定义相机全失败） | Slice plot feature 内部定义切面 |
| C3 | 切片数属性名易猜错（`quickplanesnumber`? 不存在） | 轴相关 `quickznumber`/`quickynumber`/`quickxnumber`；3D 装饰在 Image 导出节点 `title3d`/`legend3d`/`axes3d` |
| C4 | 猜 plot/export 属性名几十次盲试 | `feat.properties()` 枚举全名 |
| D1 | comsol-mcp 无热重载 | 改 java 即时生效；改 server.py 需重启 MCP `/mcp` |
| D2 | JVM 启动 5-10s/次 | iterate 模式一 JVM 跑 N 值摊薄 |

## 工具速查（`comsol_*` MCP）

| 工具 | 阶段 | 关键参数 |
|---|---|---|
| `inspect_model` / `dump_model` | 1-2 | 列参数/物理/材料/研究/数据集；dump 确认域编号与 BC 选择 |
| `build_model` | 3-6 | Java body 操作 `model` 变量；几何/材料/物理/网格/研究全在这建 |
| `add_dispersion_material` | 4 | kind=lorentz/drude/lorentz+drude；符号已修正 + 自检 |
| `material_info` | 4 | 列材料 identifier，防 A1 |
| `physics_feature` | 5 | list/create/update/delete 任意 BC/port 属性；`probe_feature` 探名 |
| `param_sweep` | 6 | mode="iterate"（关键，防 B2） |
| `run_study` | 6 | 单次解；设参 + 解 + 可选存 |
| `get_result` / `eval_aggregate` | 7 | `["abs(ewfd.S11)^2"]` 提 R(ω)；eval_aggregate sweep-aware 按 outer 分组 |
| `export_slice_3d` | 7 | 3D 原生切片图（Slice plot feature，绕开 C2） |
| `export_image` | 7 | 2D 原生图（Surface）；3D 用 export_slice_3d |

## 收到请求后的路由

1. **判阶段**：用户在「建模/材料/物理/网格/扫描/出图」哪一步？还是从头复现一篇论文？
2. **读对应章节** + 对应 principles 代号陷阱。
3. **查素材库**：Proscia 项目里有现成 body/脚本/报告可参照，别从零写。
4. **执行**：调对应 `comsol_*` 工具做硬操作；图像过 visionpro 验证。
5. **沉淀**：踩到新陷阱 → 更新 `principles.md` + `journal.md` + `results.tsv`，有可复现就写 `tests/` 回归 + `verify.sh` 守门（见 `.comsol-mcp/EVOLUTION.md` 闭环）。

## 默认值（光学/物理研究者）

- 期刊偏 Nature Photonics / ACS Photonics / PRB / APL / eLight。
- 频率/波数：COMSOL 输出裸数（无 THz/1/cm 标签），自己换算；`wn[cm⁻¹] → f[THz] = wn × 0.0299792458`。
- 出图 MATLAB 优先。
- 说中文。