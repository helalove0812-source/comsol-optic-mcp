// ============================================================
// 2D Perfect-Absorber patch array — mid-IR, 2D x-y, x-periodic, out-of-plane E_z
// base_model = FP_ahn_conv_M2_L744.mph (a SOLVED 2D out-of-plane ewfd with a PROPER
//   "RefractiveIndex" property group on mat1/Au-Ordal + std1). Reuse its ewfd and
//   duplicate mat1 for every PA layer — this is the FP_ahn converge pattern.
//
// WHY THIS BASE (learned the hard way, ~12 failed attempts):
//   The ewfd "Wave Equation, Electric" reads property 'n' from each material's
//   RefractiveIndex group ONLY if that group is PROPER (group tag "RefractiveIndex",
//   identifier "rfi"). A group made by propertyGroup().create("rg1","RefractiveIndex")
//   is MALFORMED (its groupname/identifier becomes the tag "rg1", not "RefractiveIndex")
//   => ewfd tries to read 'n', fails => "#Undefined material property 'n' required by
//   Wave Equation, Electric 1" at SOLVE (compile OK). Verified dead-ends: the Kim
//   base (all materials malformed rg1), API-created rfi, def-only epsr, explicit
//   wee1 n/ki, AND add_dispersion_material's create("rg1") "Common" materials — ALL
//   "Undefined n". The ONLY way to get a proper RefractiveIndex group in batch is
//   material.duplicate("newTag","mat1") (copies mat1's proper group; tag becomes
//   "RefractiveIndex", identifier "rfi"), then overwrite n/ki via
//   propertyGroup("RefractiveIndex"). (FP_ahn converge body:96-109, memory
//   comsol-material-duplicate-fix.) The Kim base ewfd also can't be reused: removing
//   its colliding w/t params (needed to clear Duplicate_variable_name) then exposes
//   the malformed-rg1 "Undefined n". The FP base has NO w/t collision and proper rfi.
//
// KEY: a Floquet PERIODIC PORT (PortType="Periodic") needs a 2-port periodic unit cell
//   (ports on BOTH top and bottom define the periodic propagation direction). With an
//   OPAQUE Au ground between the ports => T≈0, A = 1 - R, and the R dip = PA.
//
// Stack (y bottom->top), x-periodic with period P:
//   AirBot (port2) | Au ground (tAuB, opaque) | lossy spacer (d) | Au patch (wStr x tAu,
//   centered) | AirTop (port1 input). Resonance: out-of-plane E_z patch-ground parallel-
//   plate LC; R dips where the resonance couples into the lossy spacer.
// Extraction: get_reflection_2d reads abs(ewfd.S11)^2 (R), abs(ewfd.S21)^2 (T~0).
// ============================================================

// ---- 1) Parameters ----
model.param().set("P",      "3.0[um]",   "unit-cell period (x); subwavelength vs mid-IR lambda~4.4um");
model.param().set("wStr",   "1.5[um]",   "Au patch width (x)");
model.param().set("tAirBot","4[um]",     "bottom air (port2 separation)");
model.param().set("tAuB",   "0.1[um]",   "Au ground thickness (opaque at mid-IR; skin depth ~0.03um)");
model.param().set("d",      "1.5[um]",   "lossy spacer thickness");
model.param().set("tAu",    "0.03[um]",  "Au patch thickness");
model.param().set("tAir",   "6[um]",     "top air (port1 separation)");
model.param().set("nSp",    "2.0",       "lossy spacer refractive index (real)");
model.param().set("kSp",    "0.1",       "lossy spacer extinction (moderate, for a sharp resonance)");
model.param().set("m",      "P/2",       "half domain width (x = P/2)");
model.param().set("H",      "tAirBot+tAuB+d+tAu+tAir", "total stack height (y)");
model.param().set("mgn",    "P/10",      "box-selection margin");
model.param().set("thk",    "P/1000",    "box-selection thickness");
model.param().set("wnMin",   "400[1/cm]", "sweep start wavenumber");
model.param().set("wnMax",   "3500[1/cm]","sweep stop wavenumber");
model.param().set("wnStep",  "10[1/cm]", "sweep step");
model.param().set("lda0",   "1/wnMin",   "longest in-band wavelength (mesh ref, m)");

// ---- 2) Rebuild geometry: 5 rects (bottom->top) ----
var g = model.component("comp1").geom("geom1");
try { g.lengthUnit("µm"); } catch (Throwable e) {}
for (String ft : g.feature().tags()) if (!ft.equals("fin")) { try { g.feature().remove(ft); } catch (Throwable e) {} }
var rAirBot = g.create("rAirBot", "Rectangle");
rAirBot.set("size", new String[]{"P", "tAirBot"});
rAirBot.set("pos",  new String[]{"-P/2", "0"});
var rAuB = g.create("rAuB", "Rectangle");
rAuB.set("size", new String[]{"P", "tAuB"});
rAuB.set("pos",  new String[]{"-P/2", "tAirBot"});
var rSp = g.create("rSp", "Rectangle");
rSp.set("size", new String[]{"P", "d"});
rSp.set("pos",  new String[]{"-P/2", "tAirBot+tAuB"});
var rPatch = g.create("rPatch", "Rectangle");
rPatch.set("size", new String[]{"wStr", "tAu"});
rPatch.set("pos",  new String[]{"-wStr/2", "tAirBot+tAuB+d"});
var rAirTop = g.create("rAirTop", "Rectangle");
rAirTop.set("size", new String[]{"P", "tAu+tAir"});
rAirTop.set("pos",  new String[]{"-P/2", "tAirBot+tAuB+d"});
g.run();

// ---- 3) Box selections ----
var comp = model.component("comp1");
for (String st : new String[]{"bBot","bTop","bLeft","bRight","dAuB","dSp","dPatch","dAirBot","dAirTop","dAll"}) {
  try { comp.selection().remove(st); } catch (Throwable e) {}
}
// domain selections (entitydim 2, intersects)
String[][] doms = new String[][]{
  {"dAuB",   "-P/2+mgn", "P/2-mgn", "tAirBot+tAuB*0.25", "tAirBot+tAuB*0.75"},
  {"dSp",    "-P/2+mgn", "P/2-mgn", "tAirBot+tAuB+d*0.3", "tAirBot+tAuB+d*0.7"},
  {"dPatch", "-wStr/2+mgn", "wStr/2-mgn", "tAirBot+tAuB+d+tAu*0.3", "tAirBot+tAuB+d+tAu*0.7"},
  {"dAirBot","-P/2+mgn", "P/2-mgn", "tAirBot*0.3", "tAirBot*0.7"},
  {"dAirTop","-P/2+mgn", "P/2-mgn", "tAirBot+tAuB+d+tAu+tAir*0.3", "tAirBot+tAuB+d+tAu+tAir*0.7"},
};
int[][] dIds = new int[doms.length][];
for (int i = 0; i < doms.length; i++) {
  comp.selection().create(doms[i][0], "Box");
  comp.selection(doms[i][0]).set("entitydim", "2");
  comp.selection(doms[i][0]).set("condition", "intersects");
  comp.selection(doms[i][0]).set("xmin", doms[i][1]); comp.selection(doms[i][0]).set("xmax", doms[i][2]);
  comp.selection(doms[i][0]).set("ymin", doms[i][3]); comp.selection(doms[i][0]).set("ymax", doms[i][4]);
  dIds[i] = comp.selection(doms[i][0]).entities();
}
int[] auBDoms = dIds[0], spDoms = dIds[1], patchDoms = dIds[2], airBotDoms = dIds[3], airTopDoms = dIds[4];

// boundary selections (entitydim 1, inside)
String[][] bnds = new String[][]{
  {"bBot",  "-P/2", "P/2", "-thk", "thk"},
  {"bTop",  "-P/2", "P/2", "H-thk", "H+thk"},
  {"bLeft", "-P/2-thk", "-P/2+thk", "-mgn", "H+mgn"},
  {"bRight","P/2-thk", "P/2+thk", "-mgn", "H+mgn"},
};
int[][] bIds = new int[bnds.length][];
for (int i = 0; i < bnds.length; i++) {
  comp.selection().create(bnds[i][0], "Box");
  comp.selection(bnds[i][0]).set("entitydim", "1");
  comp.selection(bnds[i][0]).set("condition", "inside");
  comp.selection(bnds[i][0]).set("xmin", bnds[i][1]); comp.selection(bnds[i][0]).set("xmax", bnds[i][2]);
  comp.selection(bnds[i][0]).set("ymin", bnds[i][3]); comp.selection(bnds[i][0]).set("ymax", bnds[i][4]);
  bIds[i] = comp.selection(bnds[i][0]).entities();
}
int[] botBnd = bIds[0], topBnd = bIds[1], leftBnd = bIds[2], rightBnd = bIds[3];

// Au = ground + patch ; air = bot + top ; allDoms for wee1/init1
java.util.List<Integer> auList = new java.util.ArrayList<>();
for (int x : auBDoms) auList.add(x); for (int x : patchDoms) auList.add(x);
int[] auDoms = new int[auList.size()]; for (int i=0;i<auList.size();i++) auDoms[i]=auList.get(i);
java.util.List<Integer> airList = new java.util.ArrayList<>();
for (int x : airBotDoms) airList.add(x); for (int x : airTopDoms) airList.add(x);
int[] airDoms = new int[airList.size()]; for (int i=0;i<airList.size();i++) airDoms[i]=airList.get(i);
java.util.List<Integer> allList = new java.util.ArrayList<>();
for (int x : auBDoms) allList.add(x); for (int x : spDoms) allList.add(x);
for (int x : patchDoms) allList.add(x); for (int x : airBotDoms) allList.add(x); for (int x : airTopDoms) allList.add(x);
int[] allDoms = new int[allList.size()]; for (int i=0;i<allList.size();i++) allDoms[i]=allList.get(i);
int[] perBnd = new int[leftBnd.length + rightBnd.length];
System.arraycopy(leftBnd,0,perBnd,0,leftBnd.length);
System.arraycopy(rightBnd,0,perBnd,leftBnd.length,rightBnd.length);

// ---- 4) Materials: duplicate mat1 (Au-Ordal, PROPER RefractiveIndex group) for every layer ----
// Reuse mat1 for Au (ground+patch) as-is (Au-Ordal n,k is passive literature data). Duplicate
// mat1 -> matSp (lossy spacer) and matAr (air), overwriting n/ki. The duplicate preserves the
// proper "RefractiveIndex" group (tag "RefractiveIndex", identifier "rfi") that ewfd reads 'n'
// from. Unassign the FP base's other materials (mat2 CaF2, mat4 Liquid, mat5 Cr, mat6 SiO2).
comp.material("mat1").selection().set(auDoms);                       // Au ground + patch
try { comp.material().remove("matSp"); } catch (Throwable e) {}
comp.material().duplicate("matSp", "mat1");
comp.material("matSp").label("lossy spacer (nSp, kSp)");
comp.material("matSp").propertyGroup("RefractiveIndex").set("n", "nSp");
comp.material("matSp").propertyGroup("RefractiveIndex").set("ki", "kSp");
comp.material("matSp").selection().set(spDoms);
try { comp.material().remove("matAr"); } catch (Throwable e) {}
comp.material().duplicate("matAr", "mat1");
comp.material("matAr").label("air");
comp.material("matAr").propertyGroup("RefractiveIndex").set("n", "1");
comp.material("matAr").propertyGroup("RefractiveIndex").set("ki", "0");
comp.material("matAr").selection().set(airDoms);
for (String m : new String[]{"mat2","mat4","mat5","mat6"}) { try { comp.material(m).selection().set(new int[]{}); } catch (Throwable e) {} }

// ---- 5) Physics: REUSE the base ewfd (FP_ahn pattern), reconfigure for PA ----
var ewfd = comp.physics("ewfd");
for (String f : new String[]{"sctr1","port1","port2","sctr_out","pec1"}) {
  try { ewfd.feature().remove(f); } catch (Throwable e) {}
}
try { ewfd.prop("BackgroundField").set("SolveFor","fullField"); } catch (Throwable e) {}
try { ewfd.prop("BackgroundField").set("WaveType","userdef"); } catch (Throwable e) {}
try { ewfd.prop("BackgroundField").set("Ebg", new String[]{"0","0","0"}); } catch (Throwable e) {}
try { ewfd.feature("wee1").selection().set(allDoms); } catch (Throwable e) { System.out.println("wee1_sel_fail\t"+e.getMessage()); }
try { ewfd.feature("init1").selection().set(allDoms); } catch (Throwable e) {}
var port1 = ewfd.feature().create("port1","Port");   // TOP input
port1.selection().set(topBnd); port1.set("PortType","Periodic"); port1.set("PortName","1"); port1.set("E0", new String[]{"0","0","1"});
var port2 = ewfd.feature().create("port2","Port");   // BOTTOM passive
port2.selection().set(botBnd); port2.set("PortType","Periodic"); port2.set("PortName","2");
ewfd.feature("pc1").selection().set(perBnd);          // x-periodicity (normal incidence)

// ---- 6) Mesh: Free Triangular, length-resolved + patch/spacer/Au-skin refined ----
try { comp.mesh().remove("mesh1"); } catch (Throwable e) {}
comp.mesh().create("mesh1","geom1");
var mh = comp.mesh("mesh1");
var msz = mh.feature().create("msz","Size");
msz.set("hmaxactive","on"); msz.set("hmax","lda0/12");
msz.set("hminactive","on"); msz.set("hmin","lda0/60");
var szPatch = mh.feature().create("mszPatch","Size");
try { szPatch.selection().geom("geom1", 2).set(patchDoms); } catch (Throwable e) { System.out.println("szPatch_sel_fail\t"+e.getMessage()); }
szPatch.set("hmaxactive","on"); szPatch.set("hmax","wStr/12");
szPatch.set("hminactive","on"); szPatch.set("hmin","tAu/3");
var szAuB = mh.feature().create("mszAuB","Size");
try { szAuB.selection().geom("geom1", 2).set(auBDoms); } catch (Throwable e) { System.out.println("szAuB_sel_fail\t"+e.getMessage()); }
szAuB.set("hmaxactive","on"); szAuB.set("hmax","tAuB/3");
szAuB.set("hminactive","on"); szAuB.set("hmin","tAuB/6");
var szSp = mh.feature().create("mszSp","Size");
try { szSp.selection().geom("geom1", 2).set(spDoms); } catch (Throwable e) { System.out.println("szSp_sel_fail\t"+e.getMessage()); }
szSp.set("hmaxactive","on"); szSp.set("hmax","d/12");
szSp.set("hminactive","on"); szSp.set("hmin","d/30");
mh.feature().create("ftri","FreeTri");
mh.run();
System.out.println("meshNumElem\t" + mh.getNumElem());

// ---- 7) Study: mid-IR freq sweep ----
var std = model.study("std1");
var fr = std.feature("freq");
fr.set("plist", "range(c_const*wnMin, c_const*wnStep, c_const*wnMax)");
try { fr.setSolveFor("/physics/ewfd", true); } catch (Throwable e) {}
try { std.feature().remove("psweep"); } catch (Throwable e) {}

// ---- 8) Report ----
BridgeUtil.section("GEOM");
System.out.println("P\t" + model.param().evaluate("P"));
System.out.println("wStr\t" + model.param().evaluate("wStr"));
System.out.println("H\t" + model.param().evaluate("H"));
System.out.println("lda0\t" + model.param().evaluate("lda0"));
System.out.println("auBDoms\t" + java.util.Arrays.toString(auBDoms));
System.out.println("spDoms\t" + java.util.Arrays.toString(spDoms));
System.out.println("patchDoms\t" + java.util.Arrays.toString(patchDoms));
System.out.println("airBotDoms\t" + java.util.Arrays.toString(airBotDoms));
System.out.println("airTopDoms\t" + java.util.Arrays.toString(airTopDoms));
System.out.println("auDoms\t" + java.util.Arrays.toString(auDoms));
System.out.println("airDoms\t" + java.util.Arrays.toString(airDoms));
System.out.println("allDoms\t" + java.util.Arrays.toString(allDoms));
System.out.println("botBnd\t" + java.util.Arrays.toString(botBnd));
System.out.println("topBnd\t" + java.util.Arrays.toString(topBnd));
System.out.println("perBnd\t" + java.util.Arrays.toString(perBnd));
System.out.println("done");