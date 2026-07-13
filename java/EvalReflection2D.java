import com.comsol.model.Model;
import com.comsol.model.NumericalFeature;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** Extract R(omega) / T(omega) / A(omega) from a SOLVED 2D (or 3D) ewfd model
 *  that uses Floquet PERIODIC PORTS — via the port S-parameters (the method
 *  Proscia 3D used, SweepLxLy.java.body:42-48):
 *
 *    R = |ewfd.S11|^2   (reflection, port 1 -> port 1)
 *    T = |ewfd.S21|^2   (transmission, port 1 -> port 2; best-effort, may be
 *                        undefined if there is no port 2, e.g. a back-mirror PA)
 *    A = ewfd.Atotal    (absorbed power fraction; COMSOL built-in)
 *
 *  This is the 2D/3D Floquet-port counterpart of EvalReflection (1D
 *  out-of-plane field-ratio method via ewfd.normErel under a Scattering BC).
 *  Use THIS helper when the model has Periodic Ports; use EvalReflection for
 *  1D out-of-plane Scattering-BC models.
 *
 *  Evaluation is a `result().numerical().create(tag,"Global")` eval — a
 *  numerical (not geometry) op, so it is ALLOWED in headless batch (unlike
 *  plot-feature selection setting, see principles C2).
 *
 *  Handles parametric sweeps like EvalAggregate: finds the parametric dataset,
 *  iterates its outer solution tags, emits one R/T/A curve per distinct swept
 *  value (deduped). For a plain frequency sweep, emits a single curve.
 *
 *  Input props:
 *    model=<path to solved .mph>
 *    dset=<optional explicit dataset tag>
 *    outer=<optional swept param name, e.g. L>   (enables per-outer curves + dedup)
 *    outer_value=<optional, emit only the curve whose outer ~= this>
 *
 *  Emits sections: META, REFL, TRANS, ABS, FREQ, DIPS, CONSERVATION.
 *  Ports must be named "1" and "2" (COMSOL default) for S11/S21.
 */
public class EvalReflection2D {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String input = in.getProperty("model");
    if (input == null) { BridgeUtil.fail("missing 'model'"); return null; }
    Model model = ModelUtil.load("Model", input);

    String outer = in.getProperty("outer", "");
    String outerValueFilter = in.getProperty("outer_value", "");
    String dsetProp = in.getProperty("dset", "");

    // 1. choose dataset: explicit > parametric (label contains "parametric") > first
    String[] dtags = model.result().dataset().tags();
    String dsetTag = dsetProp;
    if (dsetTag == null || dsetTag.isEmpty()) {
      for (String t : dtags) {
        if (model.result().dataset(t).label().toLowerCase().contains("parametric")) { dsetTag = t; break; }
      }
    }
    if ((dsetTag == null || dsetTag.isEmpty()) && dtags.length > 0) dsetTag = dtags[0];
    if (dsetTag == null || dsetTag.isEmpty()) { BridgeUtil.fail("no dataset found"); return null; }
    var dset = model.result().dataset(dsetTag);

    // 2. enumerate outer solution tags on the dataset
    List<String> solTags = new ArrayList<>();
    try {
      String[] allowed = dset.getAllowedPropertyValues("solution");
      if (allowed != null) {
        for (String s : allowed) if (!"none".equalsIgnoreCase(s)) solTags.add(s);
      }
    } catch (Throwable ignored) {}
    if (solTags.isEmpty()) solTags.add(""); // non-parametric: single run

    BridgeUtil.section("META");
    System.out.println("dataset\t" + dsetTag + "\t" + model.result().dataset(dsetTag).label());
    System.out.println("nsoltags\t" + solTags.size());
    System.out.println("expr_R\tabs(ewfd.S11)^2\texpr_T\tabs(ewfd.S21)^2\texpr_A\tewfd.Atotal");
    System.out.println("outer\t" + (outer.isEmpty() ? "(none)" : outer));

    Set<String> seenOuter = new HashSet<>();
    boolean hasOuter = !outer.isEmpty();
    boolean hasFilter = !outerValueFilter.isEmpty();
    double filterVal = hasFilter ? Double.parseDouble(outerValueFilter) : 0;

    // accumulators for dip detection on the (first / filtered) R curve
    double[] Rcurve = null, Fcurve = null;

    for (String solTag : solTags) {
      if (!solTag.isEmpty()) {
        try { dset.set("solution", solTag); } catch (Throwable ignored) {}
      }
      double[] R = runGlobal(model, dsetTag, "abs(ewfd.S11)^2");
      double[] T = runGlobal(model, dsetTag, "abs(ewfd.S21)^2");   // best-effort: empty if no port 2
      double[] A = runGlobal(model, dsetTag, "ewfd.Atotal");       // best-effort
      double[] F = runGlobal(model, dsetTag, "freq");
      double outerVal = hasOuter ? runGlobalFirst(model, dsetTag, outer) : Double.NaN;
      String outerKey = hasOuter ? fmtKey(outerVal) : solTag;

      if (hasFilter && !Double.isNaN(outerVal) && Math.abs(outerVal - filterVal) > 1e-9) continue;
      if (hasOuter) {
        if (seenOuter.contains(outerKey)) continue;
        boolean allZero = true;
        for (double v : R) if (Math.abs(v) > 1e-30) { allZero = false; break; }
        if (allZero) continue;              // drop degenerate zero solutions
        seenOuter.add(outerKey);
      }

      int n = F.length;
      // emit per-point rows; R always, T/A only where available
      BridgeUtil.section("REFL");
      System.out.println("soltag\touter\tfreq_Hz\twavenumber_cm-1\tR");
      for (int p = 0; p < n; p++) System.out.println(solTag + "\t" + (hasOuter ? fmtVal(outerVal) : "")
          + "\t" + fmtVal(F[p]) + "\t" + wn(F[p]) + "\t" + fmtVal(at(R, p)));
      if (T.length == n) {
        BridgeUtil.section("TRANS");
        System.out.println("soltag\touter\tfreq_Hz\twavenumber_cm-1\tT");
        for (int p = 0; p < n; p++) System.out.println(solTag + "\t" + (hasOuter ? fmtVal(outerVal) : "")
            + "\t" + fmtVal(F[p]) + "\t" + wn(F[p]) + "\t" + fmtVal(T[p]));
      } else {
        BridgeUtil.section("TRANS"); System.out.println("# no S21 (no port 2 / T undefined)");
      }
      if (A.length == n) {
        BridgeUtil.section("ABS");
        System.out.println("soltag\touter\tfreq_Hz\twavenumber_cm-1\tAtotal");
        for (int p = 0; p < n; p++) System.out.println(solTag + "\t" + (hasOuter ? fmtVal(outerVal) : "")
            + "\t" + fmtVal(F[p]) + "\t" + wn(F[p]) + "\t" + fmtVal(A[p]));
      } else {
        BridgeUtil.section("ABS"); System.out.println("# ewfd.Atotal unavailable");
      }
      // FREQ axis (wavenumber cm-1) for convenience
      BridgeUtil.section("FREQ");
      System.out.println("soltag\tfreq_Hz\twavenumber_cm-1");
      for (int p = 0; p < n; p++) System.out.println(solTag + "\t" + fmtVal(F[p]) + "\t" + wn(F[p]));

      // keep first emitted R curve for dip + conservation summary
      if (Rcurve == null && n > 0) { Rcurve = R; Fcurve = F; }
    }

    // DIPS + CONSERVATION on the representative (first) R curve
    if (Rcurve != null && Fcurve != null) {
      int n = Rcurve.length;
      BridgeUtil.section("DIPS");
      System.out.println("wn_cm1\tR_min\tidx");
      int gminIdx = 0;
      for (int i = 0; i < n; i++) if (Rcurve[i] < Rcurve[gminIdx]) gminIdx = i;
      // local minima below 0.97 (Proscia SweepLxLy.java.body:60)
      for (int i = 1; i < n - 1; i++) {
        if (Rcurve[i] < Rcurve[i - 1] && Rcurve[i] < Rcurve[i + 1] && Rcurve[i] < 0.97) {
          System.out.println(wn(Fcurve[i]) + "\t" + fmtVal(Rcurve[i]) + "\t" + i);
        }
      }
      System.out.println("global_min\t" + wn(Fcurve[gminIdx]) + "\t" + fmtVal(Rcurve[gminIdx]) + "\t" + gminIdx);

      BridgeUtil.section("CONSERVATION");
      double rOff = n > 0 ? Rcurve[0] : Double.NaN;
      double rMin = Rcurve[gminIdx];
      System.out.println("R_offresonance\t" + fmtVal(rOff));
      System.out.println("R_min\t" + fmtVal(rMin));
      System.out.println("A_max_est\t" + fmtVal(1.0 - rMin));   // A ~= 1 - R when T=0 (back mirror)
    } else {
      BridgeUtil.section("DIPS"); System.out.println("# no R curve extracted");
    }

    return model;
  }

  private static double[] runGlobal(Model m, String dsetTag, String expr) {
    String tag = "g2_" + System.currentTimeMillis() + "_" + (int)(Math.random()*100000);
    try { m.result().numerical().remove(tag); } catch (Throwable ignored) {}
    try {
      NumericalFeature gf = m.result().numerical().create(tag, "Global");
      gf.set("expr", new String[]{expr});
      try { gf.set("data", dsetTag); } catch (Throwable ignored) {}
      gf.run();
      double[][] re = gf.getReal();
      m.result().numerical().remove(tag);
      return (re.length > 0) ? re[0] : new double[0];
    } catch (Throwable e) { return new double[0]; }   // S21/Atotal may be undefined -> empty
  }

  private static double runGlobalFirst(Model m, String dsetTag, String expr) {
    double[] a = runGlobal(m, dsetTag, expr);
    return (a.length > 0) ? a[0] : Double.NaN;
  }

  private static double at(double[] a, int i) { return (i < a.length) ? a[i] : Double.NaN; }
  private static String wn(double fhz) {
    if (Double.isNaN(fhz) || fhz <= 0) return "nan";
    return String.format("%.4f", fhz / 29979245800.0);
  }
  private static String fmtVal(double v) { return Double.isNaN(v) ? "nan" : String.format("%.6e", v); }
  private static String fmtKey(double v) { return Double.isNaN(v) ? "nan" : String.format("%.6g", v); }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}