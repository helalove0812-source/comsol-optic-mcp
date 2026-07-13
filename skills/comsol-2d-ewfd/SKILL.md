---
name: comsol-2d-ewfd
description: 2D frequency-domain electromagnetic (ewfd) periodic-structure / patch-array / perfect-absorber simulation in COMSOL 6.4 driven headless via comsol-mcp — 2D x-y geometry with x-periodic unit cell, out-of-plane E_z, Floquet periodic ports (2-port unit cell), reflection via R=|S11|², mesh-size domain selection gotcha, R-dip perfect-absorber criterion. Crystallizes the 2026-07-14 2D mid-IR patch-on-ground perfect-absorber reproduction (Au ground / lossy spacer / Au patch, R_min=0.089 @2630 cm⁻¹). Built for an optics/physics researcher doing COMSOL metasurface / perfect absorber / patch array / mid-IR work; the 2D periodic method sees in-plane patch LC resonances (use comsol-1d-ewfd for pure axial stacks, comsol-3d-ewfd for full 3D unit cells).
triggers:
  - "2d 仿真|2d ewfd|2d comsol|建 2d|2d 建模|建2d"
  - "完美吸收体|perfect absorber|PA|patch array|纳米条|patch 阵列|金属条"
  - "周期结构|periodic structure|metasurface|超表面|unit cell|晶胞|2d 周期"
  - "floquet|周期 port|周期端口|periodic port|s11|反射谱|R(ω)|反射率"
  - "patch 谐振|LC 谐振|面内谐振|patch mode|magnetic polariton"
  - "2d 反射|2d 出图|2d 结构图|2d 电场图"
---

你是光学/物理研究者的 COMSOL 2D 频域电磁仿真副驾。"2D" 指 **x-y 几何 + x 周期单胞 + out-of-plane E_z**（E 沿 z 不变、k 沿 y 传播）。这个 skill 把 2026-07-14 2D 中红外 patch-on-ground 完美吸收体复现里**打通 2D 周期 patch 这条路**踩出的全部方法、陷阱与决策固化成一条可复用流水线，并通过 `comsol_*` MCP 工具执行硬操作。

**适用边界**：2D 周期 ewfd 看得到**面内 patch LC 谐振**（patch-地镜平行板模式），是 1D 看不到的；但 patch 沿 z 无限长，要复现真正 3D 有限 patch（如 L 形、十字）用 `comsol-3d-ewfd`。

**素材库（绝对路径引用，不复制内容）：**
- 陷阱库（开发/使用 MCP 前必读）：`/home/pengyk/Project/.comsol-mcp/principles.md`（重点 A1/A2/A3、B1/B2、**F1-F4 全节**）
- 进化账本：`/home/pengyk/Project/.comsol-mcp/journal.md`（#24-#26 是 2D PA 复现 + get_reflection_2d）+ `results.tsv`
- 真实复现产物：
  - 建模 body：`/home/pengyk/Project/.comsol-mcp/java/pa2d_body.java`（2D PA，跑通 R_min=0.089）
  - 反射提取 helper：`/home/pengyk/Project/.comsol-mcp/java/EvalReflection2D.java`（Floquet S11/ST/Atotal）
  - 数据/图：`/home/pengyk/Project/.comsol-mcp/runs/pa2d_Rvswn.dat`、`pa2d_Rvswn.png`
  - base 模型：`/home/pengyk/Project/Fabry–Pérot 微腔/01_models/FP_ahn_conv_M2_L744.mph`（proper rfi mat1 + 2D out-of-plane ewfd，可作 build base）
  - 回归：`/home/pengyk/Project/.comsol-mcp/tests/#25_get_reflection_2d`、`tests/#26_2d_pa_build`

## 铁律（每次都生效）

1. **先读 principles.md 的 F 节。** 2D 周期 ewfd 的全部坑（Floquet port 配方、mesh `.geom(2).set`、reuse-ewfd+dup-mat1、PA 配方）都在 F1-F4。见下文「核心陷阱速查」。
2. **COMSOL 用 e^{iωt} 约定。** 无源损耗 ⟹ Im(ε)≤0；Drude/Lorentz i 项取共轭。
3. **材料 RefractiveIndex 组必须 proper rfi + 重用 base ewfd。** 别用 `propertyGroup().create("rg1","RefractiveIndex")`（malformed → "Undefined material property n"，编译 OK 求解才炸）。**唯一正路**：`material.duplicate(newTag, mat1_with_rfi)`（继承 identifier rfi）再 `propertyGroup("RefractiveIndex").set("n"/"ki")` 覆写。**必须重用 base ewfd** `comp.physics("ewfd")`——全新 ewfd 丢材料绑定也 Undefined n。两条同时满足才能求解。（F3 / A1）
4. **Floquet 周期 port = 双端口周期晶胞。** `Port` + `PortType="Periodic"`，**顶/底各一个 port**（port1 输入顶面 E0={0,0,1}、port2 底面）定义传播方向，左右侧壁 `pc1` `PeriodicCondition`（正入射 kvector=0）。单 port 的 Periodic port 不成立。R=`abs(ewfd.S11)^2`、T=`abs(ewfd.S21)^2`、A=`ewfd.Atotal`。提取走 `get_reflection_2d`（helper `EvalReflection2D`，S 参量法）。（F1）
5. **mesh Size 选区必须 `.geom("geom1",2).set(int[])`。** 用 `.selection().named()` 在 batch **不绑几何** → Size 静默用全局粗网格 → patch/薄层欠分辨 → **R(ω) 无 dip**（假阴性，本复现首版踩过：粗网格 R 单调升 0.27→0.84 无 dip；改 `.geom(2).set()` 立刻出双 dip）。auditMesh 的 n 是实体数不是单元数，看不出粗细——**靠粗/细 R(ω) 对比判**。（F2 / A2）
6. **损耗别过强。** 间隔层 ki 过大（如 0.5）过阻尼 dip 变浅；中等（0.1）dip 锐。调 ki + patch 宽 wStr 找临界耦合 R_min 最低。
7. **频扫用 std1 Frequency feature；扫参用 `param_sweep(mode="iterate")`。** 别叠 Parametric feature（Invalid_property_value）。
8. **图像一律过 visionpro MCP 验证**（项目级强制规则）。

## 流水线（7 阶段）

### 阶段 1 — 清理与定位
- 确认是 2D 周期 patch（patch 沿 z 无限长 OK）还是真 3D 有限 patch。后者转 comsol-3d-ewfd。
- 选 base：复现优先用 `FP_ahn_conv_M2_L744.mph`（已有 proper rfi mat1 + 2D out-of-plane ewfd + std1，重用其 ewfd 省掉材料绑定坑）。从空模型建要自己先造一个 proper rfi 源材料（duplicate 自带或 GUI 建的）。

### 阶段 2 — Setup 验证
- `ls /home/pengyk/comsol64/bin/comsol` + Wave Optics 许可。
- **清孤儿 COMSOL 进程**：`pkill -f comsol`，否则求解变慢。

### 阶段 3 — 几何（参数化 2D 周期单胞）
- `geom().create("geom1", 2)`（或重用 base geom）+ Rectangle 分层/分块。
- PA 典型（底→顶，x 周期 P）：AirBot / Au 地镜（tAuB，opaque）/ lossy spacer（d）/ Au patch（wStr×tAu，居中）/ AirTop。patch Rectangle 宽 wStr 居中（pos x=-wStr/2），AirTop 覆盖全宽 P 含 patch 上方。
- **参数全注册 `model.param()`**：P/wStr/tAirBot/tAuB/d/tAu/tAir/nSp/kSp/wnMin/wnMax/wnStep/lda0=1/wnMin。扫 patch 用 wStr，扫损耗用 kSp。
- **域编号** Form Union 后恒定（pa2d 是 1=airBot,2=AuB,3=spacer,5=patch,4=airTop），Box 选区 intersects 取域、inside 取边。先 dump 一次确认。
- 参照：`java/pa2d_body.java`。

### 阶段 4 — 材料（e^{iωt} + duplicate mat1）
- **铁律 3**：`comp.material("mat1").selection().set(auDoms)`（Au 复用 base mat1 Ordal）；`duplicate("matSp","mat1")` 覆写 n=nSp/ki=kSp（lossy spacer）；`duplicate("matAr","mat1")` 覆写 n=1/ki=0（air）。清掉 base 其它材料选区（mat2/4/5/6 → 空数组）。
- 色散材料用 `add_dispersion_material`（符号已修 + 自检 Im≤0）——但注意它内部 `create("rg1")` 是 malformed，**对 ewfd 求解可能仍 Undefined n**；稳妥起见色散也走 duplicate 路径或先验证。

### 阶段 5 — 物理（2D out-of-plane ewfd + Floquet 双 port + 周期侧壁）
- **重用 base ewfd** `comp.physics("ewfd")`，删旧 sctr/port/pec，重设（铁律 4）：
  - `port1`（顶面输入）：`PortType="Periodic"`、`PortName="1"`、`E0={0,0,1}`（out-of-plane E_z）。
  - `port2`（底面）：`PortType="Periodic"`、`PortName="2"`。
  - `pc1`（左右侧壁）`PeriodicCondition` selection=perBnd（左右边并集）。
- `BackgroundField` SolveFor=fullField、WaveType=userdef、Ebg={0,0,0}。
- `wee1`/`init1` selection=allDoms。
- 面/域编号从 `dump_model` 读，别猜。

### 阶段 6 — 网格 + 求解 + 扫描
- **铁律 5**：FreeTri + Size features，patch/spacer/薄 Au 各自 Size 选区用 `.geom("geom1",2).set(doms)`。patch hmax=wStr/12、hmin=tAu/3；薄 Au hmax=tAuB/3；spacer hmax=d/12；全局 hmax=lda0/12、hmin=lda0/60。
- 频扫：`std1` Frequency feature `plist = range(c_const*wnMin, c_const*wnStep, c_const*wnMax)`。PA 典型 400-3500 cm⁻¹ 步 10（311 点，~24s）。
- 扫参（wStr/kSp/d）：`param_sweep(mode="iterate")`（铁律 7）。
- **成功判据**：R(ω) 在 patch LC 谐振处出现 dip，R_min < 0.2（本复现 R_min=0.089）。off-resonance R 高（>0.8）。

### 阶段 7 — 后处理 + R(ω) 图
- **R(ω) 提取**：`get_reflection_2d`（Floquet S11，2D/3D 周期 port 专用）。返回 REFL/TRANS/ABS/FREQ/DIPS（R<0.97 局部极小 + global_min）/CONSERVATION。**别用 get_reflection**（那是 1D Scattering BC 场比法，对 Periodic port 无效）。
- **MATLAB 画 R/T/A vs wn**（项目偏好）：`plot(wn,R,'b',wn,T,'g',wn,A,'r')`，标 dip 位置。见 `runs/plot_pa2d.m`。
- 图过 visionpro 验证（铁律 8）。

## 核心陷阱速查（principles.md F 节 + A/B 摘要）

| 代号 | 陷阱 | 正路 |
|---|---|---|
| F1 | 单 Periodic port 不成立 | 顶/底双端口周期晶胞；R=|S11|²，用 `get_reflection_2d` |
| F2 | mesh Size `.named()` 不绑几何 → 粗网格吞 dip | `.geom("geom1",2).set(doms)`；判粗细靠 R(ω) 对比 |
| F3 | `create("rg1",...)` malformed + 全新 ewfd 丢绑定 → "Undefined n" | `duplicate(matX,mat1_rfi)` + **重用 base ewfd** |
| F4 | 损耗过强过阻尼 dip 浅 | ki≤0.1，调 wStr 找临界耦合 |
| A1 | 同 F3 材料 identifier | `material_info` 先查；duplicate 继承 |
| A3 | 符号约定 → 增益 R>1 | e^{iωt} 取共轭；`add_dispersion_material` 已修 |
| B2 | Parametric 叠 Frequency sweep | `param_sweep(mode="iterate")` |
| E1 | 频率/波数裸数 | wn[cm⁻¹]→f[THz]=wn×0.0299792458；plist 用 `c_const*wn` |

## 工具速查（`comsol_*` MCP）

| 工具 | 阶段 | 关键参数 |
|---|---|---|
| `inspect_model` / `dump_model` | 1-2 | 列参数/物理/材料；dump 确认域/面编号 |
| `build_model` | 3-6 | Java body 操作 `model`；几何/材料/物理/网格/研究全在这建 |
| `material_info` | 4 | 列材料 identifier，防 F3/A1 |
| `physics_feature` | 5 | list/create/update Port(Periodic)/PeriodicCondition 属性 |
| `run_study` / `param_sweep` | 6 | 单解；iterate 模式扫 wStr/kSp/d |
| `get_reflection_2d` | 7 | **2D Floquet S11** R/T/A + DIPS；1D 场比用 `get_reflection` |
| `export_image` | 7 | 2D 原生场/结构图（Surface，软件渲染 -3drend sw） |

## 收到请求后的路由

1. **判是否真 2D**：patch 沿 z 无限长 OK 用本 skill；真 3D 有限 patch（L 形/十字/圆）转 comsol-3d-ewfd；纯平面多层转 comsol-1d-ewfd。
2. **判阶段**：建模/材料/物理/网格/扫描/出图 哪步？还是从头复现？
3. **读对应章节** + principles F 节陷阱。
4. **查素材库**：`pa2d_body.java` + `EvalReflection2D.java` + `runs/` 现成可参照，别从零写。
5. **执行**：调 `comsol_*` 工具做硬操作；图像过 visionpro。
6. **沉淀**：踩新陷阱 → 更新 `principles.md`(F 节) + `journal.md` + `results.tsv`，可复现就写 `tests/` + `verify.sh` 守门（见 `.comsol-mcp/EVOLUTION.md`）。

## 默认值（光学/物理研究者）

- 期刊偏 Nature Photonics / ACS Photonics / PRB / Advanced Materials / eLight。
- 频率/波数：COMSOL 输出裸数，自己换算；wn[cm⁻¹]→f[THz]=wn×0.0299792458。
- 出图 MATLAB 优先。
- 说中文。