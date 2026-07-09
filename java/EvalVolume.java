import com.comsol.model.Model;
import com.comsol.model.util.ModelUtil;
import com.comsol.model.NumericalFeature;
import java.util.*;

/** Evaluate volume-integrated expressions over given domains on a (solved) model,
 *  across all sweep points, and also emit the frequency axis (global eval of 'freq').
 *  Input props:
 *    model=<path to solved .mph>
 *    expr=<comma-separated expressions to integrate over the domains>
 *    domains=<comma-separated domain numbers, e.g. 3>
 *    unit.<i>=optional unit for expr i
 *  Output: EVAL (header + rows: point, <expr>_real, <expr>_imag ...), FREQ (freq per point).
 */
public class EvalVolume {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String input = in.getProperty("model");
    if (input == null) { BridgeUtil.fail("missing 'model'"); return null; }

    List<String> exprs = new ArrayList<>();
    List<String> units = new ArrayList<>();
    if (in.getProperty("expr") != null) {
      for (String e : in.getProperty("expr").split(",")) { e = e.trim(); if (!e.isEmpty()) { exprs.add(e); units.add(""); } }
    }
    for (int i = 0; ; i++) {
      String e = in.getProperty("expr." + i);
      if (e == null) break;
      exprs.add(e.trim());
      units.add(in.getProperty("unit." + i, ""));
    }
    if (exprs.isEmpty()) { BridgeUtil.fail("missing 'expr'"); return null; }

    List<Integer> doms = new ArrayList<>();
    String dprops = in.getProperty("domains", "");
    for (String d : dprops.split(",")) { d = d.trim(); if (!d.isEmpty()) doms.add(Integer.parseInt(d)); }
    int[] domArr = new int[doms.size()];
    for (int i = 0; i < doms.size(); i++) domArr[i] = doms.get(i);

    Model model = ModelUtil.load("Model", input);

    // --- Domain-integration numerical node. In 3D this is "Volume", in 2D "Surface"
    //     (2D domains are surfaces). Try Volume first, fall back to Surface. ---
    String vtag = "brv" + System.currentTimeMillis();
    NumericalFeature vf = null;
    try { vf = model.result().numerical().create(vtag, "Volume"); }
    catch (Throwable e) { vf = model.result().numerical().create(vtag, "Surface"); }
    vf.set("expr", exprs.toArray(new String[0]));
    String[] u = units.toArray(new String[0]);
    for (int i = 0; i < u.length; i++) if (!u[i].isEmpty()) vf.setIndex("unit", u[i], i);
    if (domArr.length > 0) {
      try { vf.selection().set(domArr); } catch (Throwable e) { /* selection API variant */ }
    }
    vf.run();

    double[][] real = vf.getReal();
    double[][] imag = vf.getImag();
    int nexpr = exprs.size();
    int npoints = (nexpr == 0 || real.length == 0) ? 0 : real[0].length;
    boolean transposed = (real.length == npoints && npoints > 0 && nexpr > 1 && real[0].length == nexpr);

    BridgeUtil.section("EVAL");
    StringBuilder hb = new StringBuilder("point");
    for (String e : exprs) hb.append("\t").append(e).append("_real");
    for (String e : exprs) hb.append("\t").append(e).append("_imag");
    System.out.println(hb);
    for (int p = 0; p < npoints; p++) {
      StringBuilder rb = new StringBuilder(String.valueOf(p));
      for (int e = 0; e < nexpr; e++) rb.append("\t").append(transposed ? real[p][e] : real[e][p]);
      for (int e = 0; e < nexpr; e++) rb.append("\t").append(transposed ? imag[p][e] : imag[e][p]);
      System.out.println(rb);
    }
    model.result().numerical().remove(vtag);

    // --- Frequency axis: global eval of 'freq' over the same sweep ---
    try {
      String gtag = "brg" + System.currentTimeMillis();
      NumericalFeature gf = model.result().numerical().create(gtag, "Global");
      gf.set("expr", new String[]{"freq"});
      gf.setIndex("unit", "Hz", 0);
      gf.run();
      double[][] fr = gf.getReal();
      int nf = fr.length == 0 ? 0 : fr[0].length;
      BridgeUtil.section("FREQ");
      for (int p = 0; p < nf; p++) System.out.println(p + "\t" + fr[0][p]);
      model.result().numerical().remove(gtag);
    } catch (Throwable t) { /* freq axis best-effort */ }

    return model;
  }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}