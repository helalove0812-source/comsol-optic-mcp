import com.comsol.model.Model;
import com.comsol.model.util.ModelUtil;
import com.comsol.model.NumericalFeature;
import java.util.*;

/** Evaluate global expressions on a (solved) model and dump values as TSV.
 *  Input props:
 *    model=<path to solved .mph>
 *    expr=<comma-separated expressions>   (and/or expr.0=, expr.1=)
 *    unit=<comma-separated units, optional>
 *  Output sections: EVAL (header + rows), PARAMVALUES (optional).
 */
public class GetResult {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String input = in.getProperty("model");
    if (input == null) { BridgeUtil.fail("missing 'model'"); return null; }

    // collect expressions
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

    Model model = ModelUtil.load("Model", input);

    // create a fresh global evaluation node
    String tag = "brg" + System.currentTimeMillis();
    NumericalFeature rf = model.result().numerical().create(tag, "Global");
    rf.set("expr", exprs.toArray(new String[0]));
    String[] u = units.toArray(new String[0]);
    for (int i = 0; i < u.length; i++) if (!u[i].isEmpty()) rf.setIndex("unit", u[i], i);

    // bind to the first Solution dataset and evaluate ALL sweep points (not just last)
    try {
      String[] dsets = model.result().dataset().tags();
      for (String d : dsets) {
        if (model.result().dataset(d).label().toLowerCase().contains("solution")
            || model.result().dataset(d).label().isEmpty()) {
          rf.set("data", d);
          break;
        }
      }
      if (rf.getString("data") == null || rf.getString("data").isEmpty()) {
        rf.set("data", model.result().dataset().tags()[0]);
      }
    } catch (Throwable t) { /* best-effort */ }
    try { rf.set("solnum", "all"); } catch (Throwable t) { /* older API */ }
    rf.run();

    double[][] real = rf.getReal();
    double[][] imag = rf.getImag();
    int nexpr = exprs.size();
    // COMSOL 6.4 with solnum="all" can flatten to real[1][nexpr*npoints].
    // Handle three layouts: normal [expr][point], transposed [point][expr],
    // flattened [1][nexpr*npoints] (expr-major: e*npoints+p  OR  point-major: p*nexpr+e).
    boolean flattened = (real.length == 1) && (nexpr > 1) && (real[0].length % nexpr == 0)
                        && (real[0].length / nexpr > 1);
    boolean transposed = !flattened && (real.length != nexpr) && (real.length > 0)
                         && (nexpr > 0) && (real[0].length == nexpr);
    int npoints;
    boolean flatExprMajor = false; // only meaningful when flattened
    if (flattened) {
      npoints = real[0].length / nexpr;
      // expr 0 is assumed freq-like (large, monotonic): if the first npoints values
      // are strictly increasing, the layout is expr-major; else point-major.
      boolean mono = true;
      for (int p = 1; p < npoints; p++) {
        if (!(real[0][p] > real[0][p - 1])) { mono = false; break; }
      }
      flatExprMajor = mono;
    } else if (transposed) {
      npoints = real.length;
    } else {
      npoints = (real.length == 0) ? 0 : real[0].length;
    }

    BridgeUtil.section("EVAL");
    // header
    StringBuilder hb = new StringBuilder("point");
    for (String e : exprs) hb.append("\t").append(e).append("_real");
    for (String e : exprs) hb.append("\t").append(e).append("_imag");
    System.out.println(hb);
    for (int p = 0; p < npoints; p++) {
      StringBuilder rb = new StringBuilder(String.valueOf(p));
      for (int e = 0; e < nexpr; e++) {
        double r;
        if (flattened) r = flatExprMajor ? real[0][e * npoints + p] : real[0][p * nexpr + e];
        else r = transposed ? real[p][e] : real[e][p];
        rb.append("\t").append(r);
      }
      for (int e = 0; e < nexpr; e++) {
        double im;
        if (flattened) im = flatExprMajor ? imag[0][e * npoints + p] : imag[0][p * nexpr + e];
        else im = transposed ? imag[p][e] : imag[e][p];
        rb.append("\t").append(im);
      }
      System.out.println(rb);
    }

    // try to emit the swept parameter values for correlation
    try {
      String[] dsets = model.result().dataset().tags();
      if (dsets.length > 0) {
        BridgeUtil.section("PARAMVALUES");
        // best-effort: not all datasets expose param values the same way
        System.out.println("ndatasets\t" + dsets.length);
        for (String d : dsets) System.out.println("dset\t" + d + "\t" + model.result().dataset(d).label());
      }
    } catch (Throwable t) { /* ignore */ }

    model.result().numerical().remove(tag);
    return model;
  }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}