# comsol-mcp 进化账本

> 对应 autophotonicdesign 的 journal.md。对象是 **MCP 工具本身**(不是某个器件)。
> 每条 = 一次仿真任务中撞到的 MCP 痛点或缺能力 + 改进方案 + 状态 + lesson。
> 状态:`[KEPT]` 已合入并验证 / `[PENDING]` 待办 / `[DISCARDED]` 不修附理由。
> 进化闭环纪律见 `EVOLUTION.md`。稳定陷阱知识库见 `principles.md`。

---

# Phase 1: Ahn FP 腔复现(2026-07-10)

来源任务:复现 Ahn 2023 Science FP 红外液体腔。见 [[fp-ahn-2026-07-10]]。

## #1 — catch 吞错,Java 错误变 silent skip
- **痛点**:用户 Java 里 `try {...} catch (Throwable e) {}` 把所有错误吞掉,求解时报 "Undefined n" 完全不知哪步错。
- **修复**:`BridgeUtil.tryStmt(String, Runnable)` 包装,build_model 默认不吞错;`strict_mode` 参数可选。compile_diagnostics 带行号 ±5 行。
- **验证(07-10)**:错 java → `compile_diagnostics: line 18 ';' expected`;tryStmt 触发 → 9 条 [ERROR] ✓
- **状态**:`[KEPT]` 2026-07-10
- **Lesson**:bridge 层永远不能吞 Throwable,否则 debug 成本 10×。

## #2 — material propertyGroup identifier 暴露 API 缺失
- **痛点**:`create("rg1","RefractiveIndex")` identifier 是 "rg1" 不是 "rfi",没 API 探,只能 dump .mph 解 zip grep XML。
- **修复**:新建 `java/MaterialInfo.java` → `material_info(mat_tag)` 返回 `[(group_name, identifier), ...]` + 当前 n/ki 值。
- **验证(07-10)**:`material_info(mat1=Au)` → `RefractiveIndex identifier=rfi, n=nr(c_const/freq)` ✓
- **状态**:`[KEPT]` 2026-07-10
- **Lesson**:任何 COMSOL "tag vs identifier" 隐式映射都要有 API 显式暴露。

## #3 — param_sweep 一键扫描(Parametric 模式)
- **痛点**:tAu 扫描反复 set_param+run_study ×4;手建 Parametric `plistarr` 格式错报 "Parameter names not consistent"。
- **修复**:新建 `java/ParamSweep.java` → `param_sweep(study, param_name, [vals])` 一键建 Parametric。
- **验证(07-10)**:minimal model RUN OK;但 FP_ahn 复杂 ewfd 报 `Invalid_property_value`(Parametric 叠在 Frequency 上)。
- **状态**:`[KEPT]`(parametric 模式)2026-07-10 → 复撞见 #9 iterate 模式补全 07-11
- **Lesson**:一键扫描必须同时处理"已有 sweep"的叠加冲突,否则只在干净模型上可用。

## #4 — run_study 不可见(solve_ms/dofs/exit_code)
- **痛点**:`run_study` 返回 `solve_ms: null`,看不出真跑没跑、多久。
- **修复**:`java/RunStudy.java` 输出 `done_ms`/`dofs`/`exit_code`;server.py 透传。
- **验证(07-10)**:tAu=10nm → solve_ms=10925, exit_code=0;但 **dofs 返回 "?" 未真读**。
- **状态**:`[KEPT]` 主体;dofs 真读见 #12 `[PENDING]`
- **Lesson**:可见性字段要么真读,要么标"unknown",别给假值。

## #5 — 频率单位不一致
- **痛点**:inspect_model 返 53.96264e+12 无 THz 标签;plist 裸数;要自己写 c_const*2260[1/cm] 换算。
- **修复**:`params_units` 工具推断参数单位(tAu→um, wn→1/cm, freq0→Hz?)。
- **验证(07-10)**:params_units 输出 ✓;但 **run_study 接 wavenumber range 字符串("1800-2600 cm⁻¹ @4cm⁻¹")自动换算未做**。
- **状态**:`[KEPT]` params_units;wavenumber range 入参见 #14 `[PENDING]`
- **Lesson**:单位推断够用,但表达式数值仍裸;真正省事要支持 wavenumber 字符串入参。

## #6 — selection 创建需手动清旧(tag 已存在)
- **痛点**:新模型 `selection().create("bBot","Box")` 报 "Tag bBot already exists"(base model 同名),要预清。
- **修复**:`BridgeUtil.clearSelection` 助手 + `ensureClearSelection`,反射调 Selection 子类。
- **验证**:**未构造真实场景验证**——反射在不同 COMSOL 版本 Selection 子类 API 可能不同。
- **状态**:`[PENDING-verify]` 助手已编译,需在真实有同名 tag 场景验证
- **Lesson**:反射 fallback 的助手必须配真实场景测试,否则编译过 ≠ 运行对。

## #7 — javac 编译错误展示原始,无行号高亮
- **痛点**:compile_error 返大段带 ANSI 源码,漏个 `}` 找不到位置。
- **修复**:`compile_diagnostics: error_line + ±5 行 snippet`,server.py build_model 透传。
- **验证(07-10)**:错 java → `line 18 ';' expected` + snippet with `>> 18 |` ✓
- **状态**:`[KEPT]` 2026-07-10
- **Lesson**:错误定位是 bridge 第一生产力,行号+上下文必备。

## #8 — 大 .mph 脏文件无清理提示
- **痛点**:FP_cavity_solved2.mph 961MB 是 v1 不收敛脏文件,跑完看 ls 才发现。
- **修复**:`java/Inspect.java` 加 `<<<FILE_INFO>>>` 段(size/mtime/has_sol);inspect_model 透传。
- **验证(07-10)**:inspect_model → `file_info: {size_mb:1.03, mtime:..., has_sol:1}` ✓
- **状态**:`[KEPT]` 2026-07-10
- **Lesson**:inspect_model 不只看模型内容,也要看文件卫生。

---

# Phase 2: refined run 复撞(2026-07-11)

来源:refined run 再撞两个真问题。见 [[mcp-optimization-2026-07-10]] 07-11 段。

## #9 — param_sweep iterate 模式(Invalid_property_value 二次复撞)
- **痛点**:#3 的 parametric 模式在 ewfd+Frequency 上报 Invalid_property_value,refined run 再撞,退化成 9×run_study。
- **修复**:`ParamSweep.java` 重写加 `mode=auto|parametric|iterate`。iterate 不建 Parametric,循环 param().set+study.run。auto 检测已有 Freq/Parametric/Batch sweep → iterate。output 含 {idx}/{value} 逐值存盘。per_value solve_ms 真实测。
- **验证(07-11)**:helper 直跑 → mode=iterate, existing_sweeps=freq=Frequency, 3 值 17.4s(旧法 ~72s+9 JVM)✓
- **状态**:`[KEPT]` 2026-07-11
- **Lesson**:同代码在 minimal OK 在复杂模型崩 → 默认 mode 要 defensive(auto 检测),不能假设干净模型。

## #10 — build_model mesh 选择审计(静默空选)
- **痛点**:mesh feature `Selection.set(int[])` 实体不存在/维度错时静默空选,网格不加密、求解不报错。
- **修复**:`BridgeUtil.meshSelect` 固化正确写法;`auditMesh(model)` 输出 `<<<MESH_AUDIT>>>`,每 feature 选中实体数 n。n=0 = 静默空选信号。build_model body 后调 auditMesh,返回 mesh_audit/mesh_empty_selections。
- **验证(07-11)**:fresh-build szMid(域2)→1, szG→2;reload M2 szLiq→1, szSiO2→2, szG→9 ✓
- **状态**:`[KEPT]` 2026-07-11;auditMesh **维度列(域/边)未加** 见 #15 `[PENDING]`
- **Lesson**:静默失败的检测必须在 build 时一次性 audit,不能等求解才暴露。注意 Python 端注入需重启 MCP。

---

# Phase 3: 散落痛点(从其他 memory 收集,待处理)

## #11 — add_dispersion_material 符号约定错(未取共轭)
- **来源**:[[comsol-ewfd-material-sign-convention]]
- **痛点**:工具按 ε(ω)=ε∞+Σ S ωTO²/(ωTO²-ω²-**-**iωγ) 写,COMSOL 用 e^{iωt}(无源损耗⟹Im(ε)≤0),i 项应取共轭(分母 **+**iωγ)。原写法 Im(ε)>0 变增益,R>1。
- **修复(2026-07-13)**:`DispMaterial.java` 翻转 i 项符号——Lorentz `-i·wγ`→`+i·wγ`,Drude `+i·wγD`→`-i·wγD`。且加 **SANITY 自检**:建材料时采样几个频点算 Re/Im(ε),`passive_check` 要求 Im(ε)≤0(无源)。增益材料现在大声失败,不再静默写进 COMSOL。
- **验证(07-13)**:DispMaterial 直跑——Lorentz 双振子 worst_Im_eps=-2.63、passive_check=PASS;Lorentz+Drude worst_Im=-0.01、PASS。实际写入 COMSOL 的 ki_expr 含 `+ i*(2*pi*freq)`(Lorentz 修对)/`- i*(2*pi*freq)`(Drude 修对)。回归 `tests/#11_dispersion_sign` PASS。
- **状态**:`[KEPT]` 2026-07-13
- **Lesson**:符号约定错是最隐蔽的 bug(给物理不可能结果 R>1 才暴露),bridge 必须有物理合理性断言。SANITY 自检 + 回归双重守:Java 参考公式算 Im≤0,且实际写入的 expr 字符串查 `+ i`/`- i` 固定符号,防 formula↔expr 漂移。

## #12 — dofs 真从 model.sol(tag).dofInfo() 读
- **来源**:mcp-optimization 07-10 遗留 + 07-11 下一轮
- **痛点**:#4 的 run_study dofs 返回 "?"。
- **修复方向**:`RunStudy.java` 调 `model.sol(tag).dofInfo()` 读真实自由度数。
- **状态**:`[PENDING]`

## #13 — run_study JVM 缓存(5-10s/次)
- **来源**:mcp-optimization 下一轮
- **痛点**:每次 comsol batch 新起 JVM 5-10s,重复 run_study 累加。
- **修复方向**:JVM 常驻缓存,提速 ~5×。param_sweep iterate 已部分摊薄。
- **状态**:`[PENDING]`

## #14 — run_study 接 wavenumber range 字符串自动换算
- **来源**:#5 遗留
- **修复方向**:`run_study` 接 "1800-2600 cm⁻¹ @ 4 cm⁻¹" 字符串,自动转 freq plist。
- **状态**:`[PENDING]`

## #15 — auditMesh 加维度列(域/边区分)
- **来源**:#10 遗留
- **修复方向**:auditMesh 输出每 feature 是域(n2)还是边(n1)选择,重载模型不重建几何时给提示。
- **状态**:`[PENDING]`

## #16 — study_status 用 hasSolution()+sol().isEmpty() 准确探测
- **来源**:mcp-optimization 07-10 遗留
- **痛点**:study_status "unsolved" 靠 tag 猜(solX tag 不一定存在)。
- **修复方向**:`model.hasSolution()` + `model.sol().isEmpty()`。
- **状态**:`[PENDING]`

## #17 — ParamSweep parametric 模式与 Frequency cross-link
- **来源**:mcp-optimization 下一轮
- **修复方向**:让 parametric 模式也能在 ewfd+freq 上跑(freq plist 引用 param 值),保留多解 dataset 供 eval_aggregate `outer=` 分组。
- **状态**:`[PENDING]` — 当前 iterate 模式已绕开,但 parametric 多解 dataset 仍缺

## #18 — 2D 几何支持(MCP 当前全假设 1D)
- **来源**:[[kim-2025-perovskite-fig1-params]] / handoff §2 优先级 1
- **痛点**:14 工具全假设 1D out-of-plane,Kim 2D 周期 patch、超表面 qBIC 都需要 2D,不够用。
- **修复方向**:build_model 模板支持 `geom().create("geom1",2)` + Rectangle;ewfd 配 "Out-of-Plane Vector Wave";2D 网格。
- **状态**:`[PENDING]` — 大工作量(1-2 session),对后续超表面/超材料也用得上

## #19 — material identifier 防护(create("rgX","RefractiveIndex") 用法检测)
- **来源**:principles A1 / [[comsol-material-duplicate-fix-2026-07-10]]
- **修复方向**:build_model body 扫描 `propertyGroup().create(tag,"RefractiveIndex")` 用法给警告,提示用 duplicate。
- **状态**:`[PENDING]` — 低优先级,material_info 已能事后查

## #20 — eval_aggregate max 角点奇异防护
- **来源**:principles B3 / [[kim-patch-mode-negative-2026-07-10]]
- **修复方向**:max 聚合加角点奇异检测或文档警告;提供 "max_excluding_boundary" 选项。
- **状态**:`[PENDING]` — 低优先级,用 integral 可绕

---

# Phase 4: 回归门落地(2026-07-13)

## #21 — verify.sh 回归门 + tests/ 复现脚本套件
- **痛点**:进化闭环第 4 步"验证"靠 Claude 每次手跑 MCP 工具看对不对,无法保证"改新功能不弄坏已修好的坑"。棘轮只靠人承诺,不靠机器证明。
- **修复**:
  - `tests/_lib.sh` — `run_helper <Class> "prop=val"` 函数,直接 `comsol batch -inputfile <Class>.class` 跑固定 helper,**绕过 MCP 协议**(不需 mcp 包/Claude/MCP server 在场,可 cron/pre-commit)。镜像 server.py::run_helper。
  - `tests/#N/run.sh` + `desc.md` — 每条 KEPT 一个复现脚本,自含断言,exit 0=PASS/1=FAIL。
  - `verify.sh` — 遍历 `tests/*/run.sh`,汇总 PASS/FAIL/SKIP/MANUAL。`COMSOL_FAST=1` 跳慢 solve 测试给快速门。
  - EVOLUTION.md 加规则:改 server.py/java 后 verify FAIL=0 才能 KEPT/commit;新 KEPT 必须同时交复现脚本,否则标回 PENDING。
- **验证(07-13)**:Type A 3 条全绿——#2 material_info(mat1 Au identifier=rfi)、#8 file_info(size_mb+has_sol)、#9 param_sweep iterate(auto 检测 freq sweep → iterate,无 Invalid_property_value)。Type B 3 条(#1 tryStmt/#7 compile_diagnostics/#10 mesh_audit)走 build_model 动态路径,放 manual.md 待 thin-client 迁移。基线 `PASS=3 FAIL=0 MANUAL=3`。
- **关键技术点**:
  - helper 直跑可行验证通过:comsol batch 不依赖 MCP server,回归测试可无人值守。
  - props 通过 `COMSOL_BRIDGE_INPUT=<input.properties>` 传(key=value,UTF-8),成功信号 stdout `<<<END>>>`。
  - 测试模型 FP_ahn_conv_M0.mph(6.8MB,mat1=Au,std1+freq,tAu=0.018[um])——Ahn 项目 unsolved 模型,适合回归。
- **状态**:`[KEPT]` 2026-07-13(框架 + 3 条 A 类;B 类待迁移)
- **Lesson**:棘轮要机器锁不是人锁——每条 KEPT 配复现脚本,改代码自动跑全套,FAIL=0 才放行。这是 autophotonicdesign orchestrate.py 自动比 metric 在 MCP 侧的真正等价。B 类(build_model 动态路径)是已知边界,先手动记账,thin-client 是下一轮。
---

# Phase 5: Proscia 3D MIM L-cavity 原生出图(2026-07-13)

来源:复现 Proscia 2023 NRL 3D MIM L-cavity 后,用户问"为何没有原生结构图/电场图"。见 [[proscia-mim-2026-07-11]]。

## #22 — batch 3D 出图被封 → export_slice_3d + Slice feature 突破
- **痛点**:3D 解的 .mph 在 headless batch 里出不了原生结构/场图。四条常路全死:(1)`export_image`(2D PlotGroup2D)拒 3D dset1 "Invalid dataset type";(2)建 CutPlane2D dataset 喂 export_image → "Operation cannot be created in this context";(3)PlotGroup3D+Surface 画全边界 → 空气外盒遮挡内部 L-patch,看不到 L 形/场局域;(4)给 Surface/Hide 设选集排除空气 → Hide 创建即封、Surface `.geom().set()`/`.set()` 全报 Entity/no-selection。还撞过 Adjacent/Box 几何选择创建即封、`entities()` 在新建选择上封。结果只剩"全边界 Surface"一条路,遮挡致死。
- **修复**:
  - 新 helper `java/ExportCutPlane.java`:PlotGroup3D + **Slice plot feature**(`quickplane`+`quickz` 定位切平面+`quickznumber=1` 切片数;切平面定义在 feature 内,渲染是 eval 非几何操作,batch 允许)→ 在 IP-Dip 间隔层中点切水平面,ewfd.normE logscale 直接画出场热点。expr=dom 切到 patch 中高画 L 形结构。
  - 新 MCP 工具 **`export_slice_3d`**(server.py)封装之,暴露 expr/solnum/quickplane/quickz/quickn/logscale/view 等。
  - 3D 装饰:Image 导出节点 `title3d`/`legend3d`/`axes3d`/`showgrid`/`options3d`(非 2D 的 title2d)。
  - 加入 build.sh 编译列表(之前漏编译,靠手 javac)。
- **验证(07-13)**:Proscia mim_v38.mph(已含 131 频解)→ 4 张原生切片图:Struct_Lpatch_slice(z=0.335,dom,L 形清晰)、Field_LP_slice(z=0.225,|E| log,solnum=38=1670cm⁻¹,**L 形高场热点精确在 L-patch 正下方**)、Field_offres_slice(1500,均匀对照)、Field_UP_slice(1850)。visionpro 验读四图均带 colorbar/title/axes,L 形与场局域可辨。pngSize 从线框 22KB→着色 124KB。详见 principles C2/C3。
- **状态**:`[KEPT]` 2026-07-13
- **Lesson**:batch 的 result 上下文封几何操作是 3D 出图最大墙——dataset/选集/几何选择三条常路全死,只剩"plot feature 内定义平面"的 Slice 一条活路。属性名全是反直觉的(quickznumber 不是 quickplanesnumber,title3d 不是 legend),靠 `.properties()` 列名而非盲试。见 [[comsol-headless-field-export-2026-07-10]] 的演进(从 kim 期 Surface 边界图到 Proscia 期 Slice 切面图)。

## #23 — probe_feature 探不到 plot/export feature(只能探 physics feature)
- **痛点**:`probe_feature(feature_type="Slice")` 报 "Unknown physics interface. Slice"——它把 feature_type 当 physics interface 建,探不了 plot feature(Slice/Surface/Volume)和 export feature(Image)的属性名。本次只能靠 build_model 里 `feat.properties()` 手工枚举。
- **修复方向**:加一个 `probe_plot_feature` 或扩 probe_feature 支持 kind=plot/export,用 `.properties()` 列全名 + 对候选名 set 测通否。把 C4 的手工招法工具化。
- **状态**:`[PENDING]` — 本次已靠 build_model+`.properties()` 绕过,工具化是下一轮便利项
- **Lesson**:猜名是最费 token 的环节;`.properties()` 一行解决。把它做成 MCP 工具(而非每次写 build_model 探)能把 3D 出图调试成本降一个量级。

---

# Phase 6: 补 2D 缺口 + 跑通 2D 完美吸收体(2026-07-14)

来源:计划 radiant-weaving-sunrise 阶段 1。目标:补 2D Floquet 反射提取缺口,跑通一次 2D PA 复现(成功判据 R_min < 0.2),再沉淀 skill + 工厂机制。journal #18 "工具全假设 1D" 的说法被探查修正:`_BUILD_TEMPLATE` 本就维度无关,Kim 2D 当初就是它建的;真正缺的是 2D Floquet 反射提取(get_reflection 是 1D 场比法)。

## #24 — EvalReflection2D helper + get_reflection_2d 工具(2D Floquet S 参量提取)
- **痛点**:2D/3D 周期单元用 Floquet **Periodic Port**,反射率是 `abs(ewfd.S11)^2`(端口 S 参量),不是 1D out-of-plane 的场比 `ewfd.normErel`。旧 `get_reflection`(helper `EvalReflection.java`)只做场比法,对 2D 周期端口模型无效。Proscia 3D 当时靠 `SweepLxLy.java.body` 手写 `result().numerical().create(tag,"Global")` 求 S11,没封装成 MCP 工具。
- **修复**:
  - 新 helper `java/EvalReflection2D.java`(仿 `EvalAggregate.java` 骨架,`getSDim` 自适应):找频率/参数 dataset,遍历外层解 tag,对每频点 `result().numerical().create(tag,"Global")` 求 `abs(ewfd.S11)^2`(R)/`abs(ewfd.S21)^2`(T)/`ewfd.Atotal`(A)。发 `<<<REFL>>>` `<<<TRANS>>>` `<<<ABS>>>` `<<<FREQ>>>`(wn_cm1)`<<<DIPS>>>`(R<0.97 局部极小 + global_min)`<<<CONSERVATION>>>`(R_off/R_min/A_max_est)。
  - 新 MCP 工具 **`get_reflection_2d`**(server.py,仿 `get_reflection` 780-808):签名 `(model_path, port1="1", port2="2", outer?, outer_value?)`,props 喂 `run_helper("EvalReflection2D", props)`,解析 6 段。docstring 注明适用边界:Periodic Port 用本工具(S 参量);Scattering BC 场比用旧 `get_reflection`。
- **验证(07-14)**:#25 回归跑通(pa2d 已解模型 → EvalReflection2D → 311 频点 R/T/A + DIPS global_min=8.9e-2)。
- **关键技术点**:Global 数值求值是 batch 允许的(非几何操作,见 C2);S 参量法对有 opaque 地镜的 PA 给 T≈1e-8、A=1-R,能量守恒自洽。
- **状态**:`[KEPT]` 2026-07-14
- **Lesson**:2D/3D 周期端口的 R 提取和 1D 场比是两套机制,封装成两个工具各管一边比强行合一干净。S 参量是 Proscia 已验证的路,搬到 helper + 工具即用。

## #25 — 2D 完美吸收体 body pa2d_body.java + 跑通复现(R_min=0.089)
- **痛点**:要在 batch 里从零建一个能求解的 2D out-of-plane ewfd PA。**材料组陷阱是最大墙**(详见 F3、A1):`propertyGroup().create("rg1","RefractiveIndex")` 建的组 malformed(groupname=tag=rg1,identifier=rg1≠rfi)→ ewfd Wave Equation 读 'n' 失败 → "#Undefined material property n required by Wave Equation, Electric 1"(编译 OK,求解才炸)。试遍:API 建 rfi 组、def epsr、显式 wee1 n/ki、全新 Common 材料、add_dispersion_material 的 create("rg1")——**全 Undefined n**。Kim base 材料全是 malformed rg1 且 w/t 参数与 ewfd 内置冲突(Duplicate_variable_name)→ 不可用。
- **修复(正路)**:用 **FP_ahn_conv_M2_L744.mph** 作 base(已解、mat1=Au-Ordal 有 **proper RefractiveIndex 组**、无 w/t 冲突、2D out-of-plane ewfd)。`java/pa2d_body.java`:① 5 矩形几何(AirBot/Au地镜/lossy spacer/Au patch/AirTop,x 周期 P);② **重用 base ewfd** `comp.physics("ewfd")`(保材料模型绑定),删旧 sctr/port,重配 port1(顶,Periodic,E0=[0,0,1])+port2(底)+pc1(左右周期);③ 材料 **duplicate mat1**→matSp(lossy spacer nSp=2 kSp=0.1)/matAr(air),Au 复用 mat1,清掉 base 的 mat2/4/5/6 选区;④ mesh Size 用 `.geom("geom1",2).set(int[])` 显式绑几何(F2);⑤ std1 频扫 400–3500 cm⁻¹ 步 10。
- **验证(07-14)**:build+solve(24s,exit 0,无 Undefined n)→ get_reflection_2d → **R_min=0.089 @ 2630 cm⁻¹**(成功判据 R_min<0.2 达成,有余量)+ 第二 dip @1990(R=0.095),off-resonance R≈0.88,T≈1e-8,A=1-R。MATLAB 出 `runs/pa2d_Rvswn.png`(visionpro 验读:双 dip 清晰、off-res 高反射、T≈0)。数据存 `runs/pa2d_Rvswn.dat`。
- **关键技术点**:
  - **粗网格吞 dip**:首版 mesh Size 用 `.selection().named()`(batch 不绑几何)→ patch/薄 Au 用粗全局网格 → R 单调升 0.27→0.84 **无 dip**。改 `.geom("geom1",2).set()` 后立刻出双 dip(F2)。auditMesh n 是实体数不是单元数,看不出粗细——靠粗/细 R(ω) 对比判。
  - 损耗别过大:kSp=0.5 过阻尼 dip 浅;降到 0.1 dip 锐到 R_min=0.089。
  - 重用 base ewfd 是关键:全新 ewfd 丢材料绑定也 Undefined n。
- **状态**:`[KEPT]` 2026-07-14
- **Lesson**:batch 里建能求解的 ewfd,材料 RefractiveIndex 组必须是 proper rfi——唯一可靠来源是 `material.duplicate` 一个已有 proper 组的源材料,别用 `propertyGroup().create`。重用 base ewfd 而非新建。这两条是 2D(乃至任意维度)ewfd 从零建模的通用铁律。

## #26 — 回归 tests #25/#26(2D PA 全流程 + build 门)
- **痛点**:阶段 1 成功判据(R_min<0.2)和材料 duplicate 正路要机器锁,防 refactor 重新引入 "Undefined n" 或丢 dip。
- **修复**:
  - `tests/#25_get_reflection_2d/run.sh`(Type B,慢):`_build_client.py` build+solve pa2d(FP base + run_study=std1)→ `EvalReflection2D` 提取 → 断言 DIPS `global_min` R<0.5。自含,只依赖稳定 FP base(#9 已用)。COMSOL_FAST 跳。
  - `tests/#26_2d_pa_build/run.sh`(Type B,快门):build pa2d 不 solve → 断言 compiled + 无 diagnostics/warnings + mesh_empty=0 + GEOM 段在。快门,FAST 也跑。
- **验证(07-14)**:`./verify.sh` → **PASS=8 FAIL=0 SKIP=1 MANUAL=1**(#25/#26 全 PASS,#22 仍 SKIP 待 3D 模型,#10 manual)。
- **状态**:`[KEPT]` 2026-07-14
- **Lesson**:慢的端到端测试(#25,~60s)锁成功判据;快的 build 门(#26,~10s)锁结构健全——两条一起,改 build_model/材料路径会被立刻抓。results.tsv 旧 PENDING "build_model 2D 几何支持"(#18)至此解决。
