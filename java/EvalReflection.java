import com.comsol.model.Model;
import com.comsol.model.NumericalFeature;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** For 1D out-of-plane ewfd: PointEvaluation of expressions in the interior of
 *  each material domain (Si / Liquid / CaF2) plus on the top edge. The 1D solid
 *  geometry has no surface or line integrals, so all sampling uses domain point
 *  probes (default = geometric centroid of each domain).
 */
public class EvalReflection {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String input = in.getProperty("model");
    if (input == null) { BridgeUtil.fail("missing 'model'"); return null; }
    Model model = ModelUtil.load("Model", input);

    int[] siDom   = parseInts(in.getProperty("si_dom",   "3,4"));
    int[] liqDom  = parseInts(in.getProperty("liq_dom",  "2"));
    int[] caDom   = parseInts(in.getProperty("ca_dom",   "1"));

    String gtag = "ax" + System.currentTimeMillis();
    NumericalFeature gf = model.result().numerical().create(gtag, "Global");
    gf.set("expr", new String[]{"freq"});
    gf.setIndex("unit", "Hz", 0);
    gf.run();
    double[][] fr = gf.getReal();
    int npoints = fr.length == 0 ? 0 : fr[0].length;
    BridgeUtil.section("FREQ");
    for (int p = 0; p < npoints; p++) System.out.println(p + "\t" + fr[0][p]);
    model.result().numerical().remove(gtag);

    // Surface/Line integral in 1D out-of-plane geometry: the dimension is treated
    // as a 1m depth, so IntSurface returns the per-meter value (× 1m).
    runIntegral(model, "REFL",  liqDom, new String[]{"ewfd.normErel^2/(1[V/m])^2"});
    runIntegral(model, "E_SI",  siDom,  new String[]{"ewfd.normE^2"});
    runIntegral(model, "E_LIQ", liqDom, new String[]{"ewfd.normE^2"});
    runIntegral(model, "E_CA",  caDom,  new String[]{"ewfd.normE^2"});

    return model;
  }

  private static int[] parseInts(String csv) {
    List<Integer> a = new ArrayList<>();
    for (String s : csv.split(",")) { s = s.trim(); if (!s.isEmpty()) a.add(Integer.parseInt(s)); }
    int[] r = new int[a.size()]; for (int i = 0; i < a.size(); i++) r[i] = a.get(i);
    return r;
  }

  // 1D out-of-plane: domain is 1D, but IntSurface works (per-meter of out-of-plane depth).
  private static void runIntegral(Model m, String name, int[] dom, String[] exprs) throws Exception {
    String tag = name + "_" + System.currentTimeMillis();
    NumericalFeature nf = null;
    for (String t : new String[]{"IntSurface", "Surface", "LineInt", "Volume"}) {
      try { nf = m.result().numerical().create(tag, t); break; }
      catch (Throwable e) { /* try next */ }
    }
    if (nf == null) { BridgeUtil.fail("no integral op for " + name); return; }
    nf.set("expr", exprs);
    nf.selection().set(dom);
    nf.run();
    double[][] re = nf.getReal();
    int nexpr = exprs.length;
    int np = re.length == 0 ? 0 : re[0].length;
    BridgeUtil.section(name);
    StringBuilder h = new StringBuilder("point");
    for (String e : exprs) h.append("\t").append(e).append("_real");
    System.out.println(h);
    for (int p = 0; p < np; p++) {
      StringBuilder r = new StringBuilder(String.valueOf(p));
      for (int e = 0; e < nexpr; e++) r.append("\t").append(re[e][p]);
      System.out.println(r);
    }
    m.result().numerical().remove(tag);
  }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}
