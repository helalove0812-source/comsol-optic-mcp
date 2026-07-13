# comsol-mcp 稳定陷阱知识库

> 这是用 comsol-mcp 时的**不变约定/陷阱库**(对应 autophotonicdesign 的 principles.md)。
> 一次成型后基本不动;新认知补到对应小节。开发/使用 MCP 前先读这里,避免重复踩坑。
> 来源:各 memory 蒸馏(comsol-material-duplicate-fix / comsol-ewfd-material-sign-convention / mcp-optimization / headless-field-export / kim-patch-mode-negative)。

## A. COMSOL Java API 陷阱(改 build_model / probe_feature 时必看)

### A1. material propertyGroup identifier ≠ tag
`mat.propertyGroup().create("rg1","RefractiveIndex")` 建出来的组,**identifier = "rg1"(= 你给的 tag)**,不是参考材料 Au 的 "rfi"。ewfd 按 identifier 找折射率,找不到 → 求解报 "Undefined material property n required by Wave Equation, Electric 1"。
- **正确**:用 `material.duplicate(matX, refMat)` 复制参考材料,identifier 自动继承 refMat 的(如 "rfi")。
- **探测**:用 `material_info(mat_tag)` 列出 `[(group_name, identifier), ...]`。
- 见 [[comsol-material-duplicate-fix-2026-07-10]]。

### A2. mesh/selection 的 `.geom(dim)` 是 setter 不是 getter
`feat.selection().set(int[])` **必须先 `.geom(geomTag, dim)`** 解析到几何+维度,否则**静默空选**——网格不加密、求解不报错,只能靠 auditMesh 抓 `n2=0`。
- **读**选择数:必须 `feat.selection().entities()` **直接读**;调 `.geom(geom,dim)` 会**重绑成空选择**(它是 setter),写成 `.geom().entities()` 永远读 0。
- **写**:用 `BridgeUtil.meshSelect(model, comp, mesh, feat, geom, dim, int[])` 固化正确写法。
- **⚠️ COMSOL 6.4 实测修正(2026-07-13)**:`.geom(geom,dim).set(new int[]{不存在实体})` 会**抛 "Illegal input vector, illegal entity number"**(被 tryStmt 暴露),**不是**静默空选。完全漏 `.geom(dim)` 则抛 "No entity dimension specified"(也被 tryStmt 暴露)。所以"静默 n=0"比想象窄——需要 selection 对象存在但解析为空且不抛(如几何重建后选择变 stale、或维度绑定错)。automated 回归 #10 因此暂留 manual,待找到真正的静默触发场景。
- 见 [[mcp-optimization-2026-07-10]] 项 2/07-11 项 2。

### A3. COMSOL 符号约定 e^{iωt}(无源损耗 ⟹ Im(ε)≤0)
Drude/Lorentz 振子的 i 项要**取共轭**,否则 Im(ε)>0 变增益,反射率 R>1(物理不可能)。
- `add_dispersion_material` **已修**(2026-07-13,journal #11 KEPT):Lorentz 分母 `+ i·wγ`、Drude 分母 `- i·wγD`,并加 `<<<SANITY>>>` 自检采样 Re/Im(ε) 要求 `passive_check` Im≤0。增益材料现在大声失败,不再静默写进 COMSOL。回归 `tests/#11_dispersion_sign` PASS。
- 见 [[comsol-ewfd-material-sign-convention]]。

## B. ewfd 求解陷阱(建模时必看)

### B1. 1D out-of-plane ewfd 看不到面内方向谐振
patch antenna 的 y 方向半波谐振(如 Kim 0.77 THz 来自 l=80µm 沿 y)是横向几何,1D z 方向多层模型**看不到**。2D 周期结构必须 `geom().create("geom1", 2)` + Rectangle + ewfd "Out-of-Plane Vector Wave"。**2D 现已支持**(2026-07-14):`build_model` 本就维度无关,补的是 2D Floquet 反射提取 `get_reflection_2d`(S11 法)+ 现成 body `java/pa2d_body.java`(2D 完美吸收体,跑通 R_min=0.089)。详见 F 小节。见 [[kim-2025-perovskite-fig1-params]] / [[kim-patch-mode-negative-2026-07-10]]。

### B2. Parametric feature 叠在 Frequency sweep 上 → Invalid_property_value
ewfd 模型已有 Frequency sweep 时,再建 Parametric feature 报 `Invalid_property_value`。用 `param_sweep(mode="iterate")`——不建 Parametric,循环 `model.param().set + study.run()`(study.run 自动重建几何/网格)。一个 JVM 跑完 N 值。`mode="auto"` 自动检测已有 sweep → iterate。见 [[mcp-optimization-2026-07-10]] 07-11 项 1。

### B3. eval_aggregate max 模式有角点奇异风险
max 聚合在几何角点场发散(如 Kim patch 角点),给虚假增强因子。用 `integral` 或多取几个点交叉验证;别只信 max。见 [[kim-patch-mode-negative-2026-07-10]]。

## C. headless COMSOL 渲染(export_image / export_slice_3d 时必看)

### C1. comsol batch -3drend sw(软件渲染),不是 ogl
ogl 硬件 GL 在 headless batch 崩在 26%。`export_image` 与 `export_slice_3d` 都固定用 `-3drend sw` 软件渲染器。见 [[comsol-headless-field-export-2026-07-10]]。

### C2. batch 的 result 上下文封禁几何操作(3D 出图最大坑,2026-07-13)
headless batch 里 `model.result()` 上下文**不能触发任何几何操作**,否则报 "The requested geometry operation is unknown or cannot be created in this context"。被封的有:
- 新建 dataset:`result().dataset().create("tag","CutPlane2D"/"CutPlane3D"/"Grid")` → 全封。**所以 `export_image`(2D)吃不了 3D 的 dset1**(PlotGroup2D 拒 3D dataset "Invalid dataset type"),也建不出 2D 切面 dataset 喂给它。
- 给 plot feature 设选择:`Hide` feature **创建即封**("Operation: Hide");`Surface`/`Slice` 的 `.selection().geom(geom,dim).set(int[])` 和 `.selection().set(int[])` 都失败(前者 "cannot be created",后者 "Entity has no selection"——plot feature 选择在 result 上下文无几何绑定)。**不能靠选集把空气外盒排除/只画 patch**。
- 新建/解析几何选择:`geom().selection().create("tag","Box"/"Adjacent")` 创建即封;新建选择的 `.entities("geom1",dim)` 也封。**注意**:几何选择(GeomObjectSelection)用 `entities(String)`/`entities(String,int)`;mesh/physics feature 的选择(Selection 接口)用 `entities(int dim)` 或 `entities()` 无参——两者不同接口,别混。
- 自定义相机视图:`view().create("vw3",3)` + 设 `position`/`target`/`up`/`projection="orthographic"` 给 top/side → **渲染出空白 PNG**(autocontext=autofit 与手动相机冲突)。**留 iso 默认视图**即可,切片虽倾斜但能看清。
- 例外(可读不可写):**已有** mesh/physics feature 的选择可用 `.selection().entities(dim)` 读出已解析实体(如 ps1 子节点 pport1=顶面[13]、fpc1/fpc2=周期面),但读出来也用不到 plot feature 选集上(被封)。

### C3. 3D 切面出图用 Slice plot feature(绕开 C2 的正路)
被 C2 堵死后,正路是 **Slice plot feature**(`pg.feature().create("sl","Slice")`):切平面定义在 plot feature 内部,渲染是 eval 操作(非几何操作),batch 允许。
- 定位切片:`set("quickplane","xy")` + `set("quickz","0.225")`(切平面坐标,model 长度单位)+ **`set("quickznumber",1)`**(切片数,**不设默认 0 → 渲染只剩线框**)。
- **属性名易猜错**:切片数属性是**轴相关**的 `quickznumber`(xy 平面)/`quickynumber`(xz)/`quickxnumber`(yz),**不是** `quickplanesnumber`(那是 "Unknown property")。`quickz` 是坐标(不是数量)。
- 3D 装饰开关在 **Image 导出**节点上:`set("title3d","on")`/`set("legend3d","on")`/`set("axes3d","on")`/`set("showgrid","on")`/`set("options3d","on")`(**不是** 2D 的 `title2d`/`legend2d`/`axes2d`,也**不是** plot group 的 `legend`);plot group 侧再 `set("showlegends","on")`+`set("axisactive","on")` 让图例/坐标有内容。
- 色标范围用 `set("rangecolormin"/"rangecolormax")`+`rangecoloractive=on`(**不是** `min`/`max`)。
- 典型配方见 `export_slice_3d` 工具文档(场热点 expr=ewfd.normE logscale + 切到间隔层 z;结构 expr=dom 切到 patch 中高)。封装好的 helper:`java/ExportCutPlane.java`(PlotGroup3D+Slice)。

### C4. `.properties()` 枚举 feature 全部属性名(猜名终结者)
任何 ResultFeature/PlotFeature/ExportFeature 都有 `.properties()` → 返回全部属性名 String[]。猜属性名(quickplanesnumber? numberofslices?)失败时,直接 `System.out.println(feat.properties())` 列全名再挑。本次靠它确认 `quickznumber`/`title3d`/`rangecolormin` 等真名,省掉几十次盲试。**改 build_model 写 plot/export 代码前先 `.properties()` 列名。**

## D. MCP 运行时约束(改完代码必看)

### D1. comsol-mcp 无热重载
`python server.py` 常驻进程。**Java 端**每次 `comsol batch` 重加载——改 `java/*.java` 即时生效。**Python 端**改 `server.py`(返回字段、模板注入)必须重启 MCP(`/mcp` 重连或重启 Claude Code)才生效。改完 server.py 提醒用户重启。

### D2. JVM 启动开销 5-10s/次
每次 `comsol batch` 新起 JVM。`run_study` 重复调用累加此开销;`param_sweep iterate` 一 JVM 跑 N 值可摊薄。JVM 缓存是下一轮候选(journal PENDING #13)。

## E. 单位/输出约定

### E1. 频率/波数裸数输出
`inspect_model` 频率返回裸数(53.96264e+12,无 THz 标签);plist 裸数字。`params_units` 工具能推断参数单位(tAu→um, wn→1/cm),但表达式数值仍需自己换算。见 [[mcp-optimization-2026-07-10]] 项 5。

## F. 2D 周期 ewfd(Floquet port / 完美吸收体,2026-07-14)

来源:补 2D 缺口,跑通一次 2D 完美吸收体复现(R_min=0.089 < 0.2 成功判据)。body 见 `java/pa2d_body.java`,图见 `runs/pa2d_Rvswn.png`。

### F1. Floquet 周期 port = 双端口周期晶胞
正入射 2D 周期单元(左右 `PeriodicCondition` `pc1`,kvector=0)用 **`Port` + `PortType="Periodic"`**,且**必须顶/底各一个 port**(port1 输入顶面、port2 底面)定义传播方向——单 port 的 Periodic port 不成立。R=`abs(ewfd.S11)^2`,T=`abs(ewfd.S21)^2`,A=`ewfd.Atotal`。底面有 opaque Au 地镜时 T≈0,A=1-R,dip 处即完美吸收。提取走 `get_reflection_2d`(helper `EvalReflection2D.java`,`result().numerical().create(tag,"Global")` 批量允许)。**1D out-of-plane + Scattering BC 的场比法**仍用旧 `get_reflection`——两者适用边界:**有 Periodic Port 用 get_reflection_2d(S 参量),有 Scattering BC 用 get_reflection(场比)**。

### F2. 2D mesh Size 选区必须 `.geom("geom1",2).set(int[])`
2D Free Triangular 网格给 `Size` feature 设域选区,**必须 `sz.selection().geom("geom1",2).set(patchDoms)`** 显式绑几何+维度——和 A2 同源坑。**用 `.selection().named("dPatch")` 引用命名选区在 batch 里不绑几何**→ Size feature 静默用全局粗网格,hmax=lda0/12=2um,patch(1.5µm)/薄 Au(0.03µm)严重欠分辨→**patch LC 谐振完全分辨不出,R(ω) 无 dip**(本任务踩过:粗网格 R 单调升 0.27→0.84 无 dip;改 `.geom(2).set()` 后立刻出 R_min=0.089 双 dip)。auditMesh 的 `n` 是"选中实体数"不是单元数,看不出粗细——**判粗细靠对比同结构粗/细网格的 R(ω) 是否出现 dip**,不靠 audit。

### F3. 2D PA body:重用 base ewfd + duplicate mat1(材料组必须 proper rfi)
2D out-of-plane ewfd 的 Wave Equation 读材料 'n':有 **proper RefractiveIndex 组**(tag="RefractiveIndex"、identifier="rfi")读 n,只有 def 组读 epsr。`propertyGroup().create("rg1","RefractiveIndex")` 建的是**malformed 组**(groupname=tag=rg1)→ ewfd 读 n 失败 → 求解报 "#Undefined material property n"。**唯一正路**:`material.duplicate("matSp","mat1")` 复制有 proper rfi 的源材料(FP_ahn_conv_M2 base 的 mat1 Au-Ordal),再 `propertyGroup("RefractiveIndex").set("n"/"ki")` 覆写。**全新 ewfd**(`physics().create`)丢材料模型绑定→也 Undefined n;**必须重用 base ewfd** `comp.physics("ewfd")` 再重配 port/pc1。pa2d 在 FP base 上重用 ewfd+dup mat1,Au(复用 mat1 Ordal)/lossy spacer(nSp,kSp)/air 三层,无 Undefined n。见 A1 / [[comsol-material-duplicate-fix-2026-07-10]]。

### F4. 2D 完美吸收体配方(mid-IR patch-on-ground)
结构(底→顶):AirBot(port2)/Au 地镜(tAuB=0.1µm,opaque)/lossy spacer(d=1.5µm,nSp=2,kSp=0.1)/Au patch(wStr=1.5µm,tAu=0.03µm)/AirTop(port1)。P=3µm 周期 < λ。out-of-plane E_z 激发 patch-地镜平行板 LC。频扫 400–3500 cm⁻¹ 步 10(311 点)。**R_min=0.089 @ 2630 cm⁻¹** + 第二 dip @1990(R=0.095),off-resonance R≈0.88。损耗别过大(kSp≤0.1)否则过阻尼 dip 变浅;patch 宽 wStr 调 LC 频。