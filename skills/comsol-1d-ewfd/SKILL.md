---
name: comsol-1d-ewfd
description: 1D (2D-geom, out-of-plane E_z) frequency-domain electromagnetic stack simulation in COMSOL 6.4 driven headless via comsol-mcp — planar multilayer Fabry-Pérot / microcavity, Scattering-Boundary field-ratio reflection R(ω)=<|E_rel|²/|E_inc|²>, Lorentzian Q extraction. Crystallizes the Ahn 2023 Science Fabry-Pérot liquid-cavity mid-IR reproduction (Au mirror / liquid cavity / CaF2 substrate, NCO stretch @2260 cm⁻¹). Built for an optics/physics researcher doing COMSOL microcavity / FP cavity / thin-film stack / Q-factor work; the 1D out-of-plane method sees axial resonances only (no in-plane patch modes — use comsol-2d-ewfd or comsol-3d-ewfd for those).
triggers:
  - "1d 仿真|1d ewfd|1d comsol|建 1d|1d 建模|多层腔|多层膜"
  - "fabry.pérot|fabry-perot|fp 腔|fp腔|微腔|microcavity|liquid cavity"
  - "散射边界|scattering boundary|sctr|场比法|normErel"
  - "反射谱|R of omega|R(ω)|反射率|反射谱|reflection spectrum"
  - "q 因子|q factor|q值|lorentzian|洛伦兹拟合|线宽|linewidth|kappa"
  - "1d 多层|平面多层|planar stack|薄膜堆|axial mode|纵模"
---

你是光学/物理研究者的 COMSOL 1D 频域电磁仿真副驾。"1D" 指 **2D 几何 + out-of-plane E_z** 的平面多层堆（x-y 截面 + 沿 y 传播、E 沿 z），不是单轴 1D。这个 skill 把 Ahn 2023 Fabry-Pérot 液腔中红外复现里**打通 1D 多层腔这条路**踩出的全部方法、陷阱与决策固化成一条可复用流水线，并通过 `comsol_*` MCP 工具执行硬操作。

**适用边界（重要）**：1D out-of-plane ewfd **看不到面内方向谐振**（如 patch 的 x/y 半波、缝谐振）——只看沿传播方向的轴向纵模（FP 模）。要复现 patch/slot/超表面面内谐振，用 `comsol-2d-ewfd`（2D 周期 patch）或 `comsol-3d-ewfd`（3D 单胞）。

**素材库（绝对路径引用，不复制内容）：**
- 陷阱库（开发/使用 MCP 前必读）：`/home/pengyk/Project/.comsol-mcp/principles.md`（重点 A1/A2/A3、B1/B2、E1）
- 进化账本：`/home/pengyk/Project/.comsol-mcp/journal.md`（#1-#8 是 1D FP 复现历程）+ `results.tsv`
- 真实复现产物：`/home/pengyk/Project/Fabry–Pérot 微腔/`
  - 建模 body：`03_scripts/plain_fp_body.java`（Scattering BC 场比法）、`03_scripts/FP_ahn_converge_body.java`（Floquet port S11 法，对照）
  - 收敛 body：`03_scripts/FP_ahn_converge_body.java`（tAu 扫描定 Q）
  - 报告/数据：`02_results/`
  - base 模型：`01_models/FP_ahn_conv_M2_L744.mph`（proper rfi 材料 + 已解，可作 build base）

## 铁律（每次都生效）

1. **先读 principles.md。** 1D ewfd 的核心坑（材料 identifier、符号约定、`.geom(dim)` setter、JVM 开销）全在里面。见下文「核心陷阱速查」。
2. **COMSOL 用 e^{iωt} 约定。** 无源损耗 ⟹ Im(ε)≤0；Drude/Lorentz i 项取共轭，否则变增益 R>1。
3. **材料 RefractiveIndex 组必须 proper rfi。** 别用 `propertyGroup().create("rg1","RefractiveIndex")`（malformed → "Undefined material property n"）。用 `material.duplicate(newTag, mat1_with_rfi)` 继承 identifier，再 `propertyGroup("RefractiveIndex").set("n"/"ki")` 覆写。`material_info` 先查 identifier。**重用 base ewfd** `comp.physics("ewfd")` 而非新建（全新 ewfd 丢材料绑定也 Undefined n）。
4. **R(ω) 用场比法（Scattering BC）。** 顶/底 `Scattering` 边界（sctr_in 入射 E0i={0,0,1}、kdir 指向腔内；sctr_out 吸收出射），`R = <ewfd.normErel²/(1[V/m])²>` 在反射侧边界或腔域积分。提取走 `get_reflection`（helper `EvalReflection`，1D 场比法）。**别和 2D Floquet S11 混**——有 Periodic Port 用 `get_reflection_2d`，有 Scattering BC 用 `get_reflection`。
5. **频扫用 iterate 模式扫参，频率扫描本身用 std1 的 Frequency feature。** ewfd 已有 Frequency sweep 时再叠 Parametric feature 报 `Invalid_property_value`；扫厚度/腔长用 `param_sweep(mode="iterate")`。
6. **Q 从 R(ω) Lorentzian 拟合。** R dip 近似 Lorentzian，半高全宽 FWHM → κ_c，Q=ω0/κ_c。别只看 dip 深度，用 MATLAB 3-Lorentzian 或单 Lorentzian 拟合稳。
7. **图像一律过 visionpro MCP 验证**（项目级强制规则）。

## 流水线（7 阶段）

### 阶段 1 — 清理与定位
- 核对论文与现有 .mph 是否同一物理（Ahn 是 1D FP 液腔纵模，不是 patch 谐振）。错配则从零重做。
- 选方法：**Scattering BC 场比法**（本 skill 主路，单入射、R=场比）或 **Floquet port S11 法**（FP_ahn_converge_body 那条，顶底双周期 port、R=|S11|²）。平面多层两者都行；场比法不需端口扫模式、更快。

### 阶段 2 — Setup 验证
- `ls /home/pengyk/comsol64/bin/comsol` + Wave Optics 许可。
- 建 1 µm 真空 + 1 Scattering BC + 1 freq sanity 解，确认 `comsol batch` 起得来。
- **清孤儿 COMSOL 进程**：残留 `comsol`/`java` 竞争 license/CPU 使求解变慢，先 `pkill -f comsol`。

### 阶段 3 — 几何（参数化平面多层）
- `geom().create("geom1", 2)` + Rectangle 分层（底→顶，如 CaF2/Au/Liquid/Au/CaF2）。每层一个 Rectangle，`size={P, t_layer}`，`pos` 累积 y。
- **参数全注册 `model.param()`**：P（周期，1D 可任意但需 >0）、各层厚 tsub/tAu/Lcav、目标 wn0、lda0=1/wn0。
- **域编号底→顶 1,2,3…**（Form Union 后拓扑不变恒定），材料/面选择按域号写。先 dump 一次确认。
- 参照：`03_scripts/plain_fp_body.java`。

### 阶段 4 — 材料（e^{iωt} 约定）
- Au（mid-IR）：Drude+Lorentz 或实测 ε（Ordal）。**重用 base 的 mat1 Au-Ordal**（已有 proper rfi）最省事。
- 液腔/介质：常数 ε=n² 或色散。`add_dispersion_material`（符号已修 + 自检 Im≤0）。
- **铁律 3**：duplicate 而非 create("rg1")。常数材料也可 duplicate mat1 后覆写 n/ki。

### 阶段 5 — 物理（1D out-of-plane ewfd + Scattering BC + 周期侧壁）
- **重用 base ewfd** `comp.physics("ewfd")`，删旧 port/sctr，重设：
  - `sctr_in`（底面，入射）：`E0i={0,0,1}`（E 沿 z，1 V/m），`kdir={0,1,0}`（+y 入腔）。
  - `sctr_out`（顶面，吸收出射）：`kdir={0,1,0}`。
  - `pc1`（左右侧壁）`PeriodicCondition`（正入射 kvector=0）——平面多层侧壁周期无害但需设以消边效应。
- `BackgroundField` SolveFor=fullField、WaveType=userdef、Ebg={0,0,0}。
- 域/面选择编号从 `dump_model` 读，别猜。

### 阶段 6 — 网格 + 求解 + 扫描
- 网格：1D 堆每层沿 y 至少 λ/(6·n) 个单元；薄 Au 层（~0.08µm）单独细 size（tAu/3）；远场粗。Size feature 选区用 `.geom("geom1",2).set(doms)`（铁律 A2，别用 `.named()`）。
- 频扫：`std1` Frequency feature，`plist = range(c_const*wnMin, c_const*wnStep, c_const*wnMax)`（波数→频率乘 c_const）。mid-IR 典型 2000-2500 cm⁻¹ 步 1-2 cm⁻¹。
- 扫参（腔长 Lcav / 镜厚 tAu）：`param_sweep(mode="iterate")`（铁律 5）。
- 分层跑：先单频 sanity（看 R dip 在目标 wn0 附近）→ 窄频扫看模式 → 扫参定 Q/收敛。

### 阶段 7 — 后处理 + Q 提取
- **R(ω) 提取**：`get_reflection`（场比法，1D Scattering BC 专用）。返回 REFL/E_SI/E_LIQ/... 段 + FREQ。也可 `eval_aggregate`/`get_result(["abs(ewfd.normErel)^2"])`。
- **Q 拟合**：R dip 近似 Lorentzian，MATLAB 单/多 Lorentzian 拟合 → ω0、κ_c、Q=ω0/κ_c。Ahn 复现 Q≈358（论文 100，差距归因 Au 镜损耗/界面粗糙度，见报告）。
- **MATLAB 画 R(ω)**（项目偏好，不用 Python）。
- 图过 visionpro 验证（铁律 7）。

## 核心陷阱速查（principles.md 摘要）

| 代号 | 陷阱 | 正路 |
|---|---|---|
| A1 | `create("rg1","RefractiveIndex")` identifier=rg1≠rfi → "Undefined n" | `material.duplicate(matX, mat1_with_rfi)` 继承；`material_info` 先查 |
| A3 | 符号约定错 → Im(ε)>0 增益 R>1 | e^{iωt} 取共轭；`add_dispersion_material` 已修 + 自检 |
| B1 | 1D out-of-plane 看不到面内谐振 | 面内 patch/slot 用 comsol-2d/3d-ewfd |
| B2 | Parametric 叠 Frequency sweep → Invalid_property_value | `param_sweep(mode="iterate")` |
| A2 | mesh Size `.named()` 不绑几何 → 静默粗网格 | `.geom("geom1",2).set(doms)` |
| D2 | JVM 启动 5-10s/次 | iterate 模式一 JVM 跑 N 值摊薄 |
| E1 | 频率/波数裸数输出 | wn[cm⁻¹]→f[THz]=wn×0.0299792458；plist 用 `c_const*wn` |

## 工具速查（`comsol_*` MCP）

| 工具 | 阶段 | 关键参数 |
|---|---|---|
| `inspect_model` / `dump_model` | 1-2 | 列参数/物理/材料/研究；dump 确认域编号与 BC 选择 |
| `build_model` | 3-6 | Java body 操作 `model`；几何/材料/物理/网格/研究全在这建 |
| `material_info` | 4 | 列材料 identifier，防 A1 |
| `add_dispersion_material` | 4 | kind=lorentz/drude；符号已修 + 自检 |
| `physics_feature` | 5 | list/create/update Scattering/PeriodicCondition 属性 |
| `run_study` / `param_sweep` | 6 | 单解；iterate 模式扫腔长/镜厚 |
| `get_reflection` | 7 | **1D 场比法** R=normErel²（Scattering BC 专用）；Floquet port 用 `get_reflection_2d` |
| `eval_aggregate` / `get_result` | 7 | 备选提 R(ω) / 场分布 |
| `export_image` | 7 | 2D 原生场图（Surface，软件渲染） |

## 收到请求后的路由

1. **判是否真 1D**：用户要的是平面多层纵模（FP/Q）还是面内 patch 谐振？后者转 comsol-2d/3d-ewfd。
2. **判阶段**：建模/材料/物理/网格/扫描/出图/Q 提取 哪步？还是从头复现？
3. **读对应章节** + principles 代号陷阱。
4. **查素材库**：Fabry-Pérot 微腔 有 plain_fp_body / converge_body / 报告可参照，别从零写。
5. **执行**：调 `comsol_*` 工具做硬操作；图像过 visionpro。
6. **沉淀**：踩新陷阱 → 更新 `principles.md` + `journal.md` + `results.tsv`，可复现就写 `tests/` + `verify.sh` 守门（见 `.comsol-mcp/EVOLUTION.md`）。

## 默认值（光学/物理研究者）

- 期刊偏 Nature Photonics / ACS Photonics / PRB / Science。
- 频率/波数：COMSOL 输出裸数，自己换算；wn[cm⁻¹]→f[THz]=wn×0.0299792458。
- 出图 MATLAB 优先。
- 说中文。