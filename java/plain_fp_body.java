// ============================================================
// Plain Fabry-Perot IR cavity (Ahn et al. 2023, Science 380, 1165)
// base_model = 超表面.mph  (reuse Au-Ordal / CaF2 / Liquid materials + ewfd + std1)
// Rebuild geom1 into a planar 5-layer stack; reassign materials & BCs.
// ============================================================

// ---- 1) Parameters ----
model.param().set("P", "2.4[um]", "lateral unit-cell width");
model.param().set("tsub", "5[um]", "CaF2 substrate/superstrate thickness");
model.param().set("tAu", "0.08[um]", "Au mirror thickness");
model.param().set("Lcav", "1.6[um]", "liquid cavity length (tunes FP mode to wn0)");
model.param().set("wn0", "2260[1/cm]", "target wavenumber (NCO stretch)");
model.param().set("lda0", "1/wn0", "target wavelength [m]");
model.param().set("H", "2*tsub + 2*tAu + Lcav", "total stack height");
model.param().set("mgn", "P/10", "box-selection margin");
model.param().set("thk", "P/1000", "box-selection thickness");

// ---- 2) Rebuild geometry: remove old features, add plain FP stack ----
var g = model.component("comp1").geom("geom1");
for (String ft : g.feature().tags()) if (!ft.equals("fin")) { try { g.feature().remove(ft); } catch (Throwable e) {} }
var r1 = g.create("r1","Rectangle"); r1.set("size", new String[]{"P","tsub"});  r1.set("pos", new String[]{"-P/2","0"});
var r2 = g.create("r2","Rectangle"); r2.set("size", new String[]{"P","tAu"});   r2.set("pos", new String[]{"-P/2","tsub"});
var r3 = g.create("r3","Rectangle"); r3.set("size", new String[]{"P","Lcav"});  r3.set("pos", new String[]{"-P/2","tsub+tAu"});
var r4 = g.create("r4","Rectangle"); r4.set("size", new String[]{"P","tAu"});   r4.set("pos", new String[]{"-P/2","tsub+tAu+Lcav"});
var r5 = g.create("r5","Rectangle"); r5.set("size", new String[]{"P","tsub"});  r5.set("pos", new String[]{"-P/2","tsub+tAu+Lcav+tAu"});
g.run();
// domains are numbered bottom-up: 1=CaF2(sub), 2=Au, 3=Liquid, 4=Au, 5=CaF2(sup)

// ---- 3) Box selections for the exterior boundaries ----
// 'inside' condition + WIDE cross-axis + THIN on-axis: a horizontal edge is fully
// in the wide x-band but the thin y-band excludes vertical sides & internal interfaces;
// a vertical edge is fully in the wide y-band but the thin x-band excludes horizontals.
// bottom edge y=0
model.component("comp1").selection().create("bBot","Box");
model.component("comp1").selection("bBot").set("entitydim","1");
model.component("comp1").selection("bBot").set("condition","inside");
model.component("comp1").selection("bBot").set("xmin","-P/2-mgn"); model.component("comp1").selection("bBot").set("xmax","P/2+mgn");
model.component("comp1").selection("bBot").set("ymin","-thk");     model.component("comp1").selection("bBot").set("ymax","thk");
int[] botBnd = model.component("comp1").selection("bBot").entities();
// top edge y=H
model.component("comp1").selection().create("bTop","Box");
model.component("comp1").selection("bTop").set("entitydim","1");
model.component("comp1").selection("bTop").set("condition","inside");
model.component("comp1").selection("bTop").set("xmin","-P/2-mgn"); model.component("comp1").selection("bTop").set("xmax","P/2+mgn");
model.component("comp1").selection("bTop").set("ymin","H-thk");    model.component("comp1").selection("bTop").set("ymax","H+thk");
int[] topBnd = model.component("comp1").selection("bTop").entities();
// left edge x=-P/2
model.component("comp1").selection().create("bLeft","Box");
model.component("comp1").selection("bLeft").set("entitydim","1");
model.component("comp1").selection("bLeft").set("condition","inside");
model.component("comp1").selection("bLeft").set("xmin","-P/2-thk"); model.component("comp1").selection("bLeft").set("xmax","-P/2+thk");
model.component("comp1").selection("bLeft").set("ymin","-mgn");     model.component("comp1").selection("bLeft").set("ymax","H+mgn");
int[] leftBnd = model.component("comp1").selection("bLeft").entities();
// right edge x=P/2
model.component("comp1").selection().create("bRight","Box");
model.component("comp1").selection("bRight").set("entitydim","1");
model.component("comp1").selection("bRight").set("condition","inside");
model.component("comp1").selection("bRight").set("xmin","P/2-thk");  model.component("comp1").selection("bRight").set("xmax","P/2+thk");
model.component("comp1").selection("bRight").set("ymin","-mgn");     model.component("comp1").selection("bRight").set("ymax","H+mgn");
int[] rightBnd = model.component("comp1").selection("bRight").entities();

BridgeUtil.section("BND");
System.out.println("bot\t" + java.util.Arrays.toString(botBnd));
System.out.println("top\t" + java.util.Arrays.toString(topBnd));
System.out.println("left\t" + java.util.Arrays.toString(leftBnd));
System.out.println("right\t" + java.util.Arrays.toString(rightBnd));

// ---- 4) Reassign materials (domain numbers, bottom-up) ----
model.component("comp1").material("mat1").selection().set(new int[]{2,4}); // Au mirrors
model.component("comp1").material("mat2").selection().set(new int[]{1,5}); // CaF2 sub/sup
model.component("comp1").material("mat4").selection().set(new int[]{3});   // Liquid cavity
try { model.component("comp1").material("mat3").selection().set(new int[]{}); } catch (Throwable e) {} // unassign Al2O3 (best-effort)
try { model.component("comp1").material().remove("mat3"); } catch (Throwable e) {} // and drop it if possible

// ---- 5) Physics: rebuild scattering (in/out) + periodic. wee1 auto-covers all domains. ----
var ewfd = model.component("comp1").physics("ewfd");
// remove old single scattering feature, add one-sided in/out
try { ewfd.feature().remove("sctr1"); } catch (Throwable e) {}
var sin = ewfd.feature().create("sctr_in","Scattering");
sin.selection().set(botBnd);
sin.set("IncidentField","EField");
sin.set("E0i", new String[]{"0","0","1"});    // incident E along z, 1 V/m
sin.set("kdir", new String[]{"0","1","0"});   // wave propagates +y (into cavity from bottom)
var sout = ewfd.feature().create("sctr_out","Scattering");
sout.selection().set(topBnd);
sout.set("IncidentField","NoIncidentField");  // no incident field (absorbing)
sout.set("kdir", new String[]{"0","1","0"});  // absorb +y-going waves
// periodic on left+right
int[] perBnd = new int[leftBnd.length + rightBnd.length];
System.arraycopy(leftBnd,0,perBnd,0,leftBnd.length);
System.arraycopy(rightBnd,0,perBnd,leftBnd.length,rightBnd.length);
ewfd.feature("pc1").selection().set(perBnd);

// ---- 6) Mesh: clear existing mesh1 features, single Size + FreeTri ----
try { model.component("comp1").mesh().remove("mesh1"); } catch (Throwable e) {}
model.component("comp1").mesh().create("mesh1", "geom1");
var mh = model.component("comp1").mesh("mesh1");
var sz1 = mh.feature().create("msz","Size");
sz1.set("hmaxactive","on"); sz1.set("hmax","lda0/8");
mh.feature().create("mtri","FreeTri");
mh.run();

BridgeUtil.section("GEOM");
System.out.println("lda0\t" + model.param().evaluate("lda0"));
System.out.println("done");