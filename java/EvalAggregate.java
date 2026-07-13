import com.comsol.model.Model;
import com.comsol.model.NumericalFeature;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** Evaluate expressions on a solved ewfd model with dimension-aware aggregation,
 *  correctly handling parametric sweeps (the common NCO qBIC case: outer sweep
 *  over a geometry param such as L, inner sweep over freq).
 *
 *  ROOT-CAUSE BACKGROUND (cost a lot to discover):
 *  - `solnum="all"` is REJECTED by NumericalFeature ("only supports constant
 *    expressions"); setting it is silently a no-op and the feature defaults to
 *    solnum=1. So do NOT rely on it.
 *  - A parametric sweep produces a "Parametric Solutions" dataset (e.g. dset2)
 *    whose OUTER (param) selection is controlled by the dataset's `solution`
 *    property (allowed values like sol1..solN), NOT by the feature's
 *    `outersolnum` (which exists but has no effect) and NOT by `solnum`.
 *    dset1 "Solution 1" holds only one outer value. Features that never set
 *    `data` defaulted to dset1 -> stuck on one L -> identical CSVs for every L.
 *
 *  RECIPE (verified on Si_qBIC_bare_v2_test_solved.mph, L={0.78,0.9} x 121 freq):
 *   1. find the parametric dataset (label contains "parametric");
 *   2. read its `solution` allowed values (sol1..solN);
 *   3. for each solution tag: set dset.solution=<tag>, run the aggregate
 *      feature + a Global eval of "freq" + (if `outer` given) a Global eval of
 *      the swept param; emit one row per (solution, freq point);
 *   4. dedup by outer-param value (sol1/sol4 duplicate L0.9, sol2/sol3 L0.78,
 *      sol3 a zero solution) keeping the first non-zero curve per distinct L.
 *
 *  Aggregates: max, min, avg, integral. Returns one value per (L, freq).
 *
 *  Input props:
 *    model=<path to solved .mph>
 *    expr=<expression>
 *    aggregate=max|min|avg|integral   (default integral)
 *    domains=<csv int list, e.g. 3,4>
 *    boundaries=<csv int list, e.g. 5>
 *    unit=<optional unit for expr>
 *    outer=<optional swept param name, e.g. L>           (enables per-L curves + dedup)
 *    outer_value=<optional, e.g. 0.9>                    (emit only the curve whose outer ~= this)
 *    dset=<optional explicit dataset tag to use>
 */
public class EvalAggregate {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String input = in.getProperty("model");
    if (input == null) { BridgeUtil.fail("missing 'model'"); return null; }
    Model model = ModelUtil.load("Model", input);

    String expr = in.getProperty("expr");
    if (expr == null || expr.isEmpty()) { BridgeUtil.fail("missing 'expr'"); return null; }
    String aggregate = in.getProperty("aggregate", "integral").toLowerCase();
    int[] domains = parseInts(in.getProperty("domains", ""));
    int[] boundaries = parseInts(in.getProperty("boundaries", ""));
    String unit = in.getProperty("unit", "");
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
    if (dsetTag == null || dsetTag.isEmpty() && dtags.length > 0) dsetTag = dtags[0];
    if (dsetTag == null || dsetTag.isEmpty()) { BridgeUtil.fail("no dataset found"); return null; }
    var dset = model.result().dataset(dsetTag);

    // 2. determine aggregate feature type + geom level.
    // Geometry dimension is read from the model via getSDim(): a 2D geom
    // (including the "1D out-of-plane" stack, which is actually built from 2D
    // Rectangles) yields dim=2; a 3D geom yields dim=3. Aggregate features are
    // picked to match the entity dimension: IntSurface/MaxSurface for 2D
    // domains, IntVolume/MaxVolume for 3D. geomLevel follows (domains=dim,
    // boundaries=dim-1), so this is correct for true 2D, 1D-out-of-plane,
    // and 3D alike.
    int dim = 2; // safe default: correct for true 2D and 1D-out-of-plane (2D entities)
    String geomTag = "geom1";
    try {
      int maxSdim = 0;
      for (String c : model.component().tags()) {
        for (String g : model.component(c).geom().tags()) {
          int sd = model.component(c).geom(g).getSDim();
          if (sd > maxSdim) { maxSdim = sd; geomTag = g; }
        }
      }
      if (maxSdim > 0) dim = maxSdim;
    } catch (Throwable e) { /* keep dim=2, geom1 */ }
    int geomLevel;
    int[] entIds;
    if (boundaries.length > 0) { geomLevel = Math.max(dim - 1, 1); entIds = boundaries; }
    else if (domains.length > 0) { geomLevel = dim; entIds = domains; }
    else { geomLevel = dim; entIds = new int[0]; }

    String ftype;
    boolean needsDenom = false;
    if (dim >= 3) {
      if ("max".equals(aggregate))          ftype = "MaxVolume";
      else if ("min".equals(aggregate))      ftype = "MinVolume";
      else if ("avg".equals(aggregate))      { ftype = "IntVolume"; needsDenom = true; }
      else if ("integral".equals(aggregate)) ftype = "IntVolume";
      else { BridgeUtil.fail("unknown aggregate: " + aggregate); return null; }
    } else {
      if ("max".equals(aggregate))          ftype = "MaxSurface";
      else if ("min".equals(aggregate))      ftype = "MinSurface";
      else if ("avg".equals(aggregate))      { ftype = "IntSurface"; needsDenom = true; }
      else if ("integral".equals(aggregate)) ftype = "IntSurface";
      else { BridgeUtil.fail("unknown aggregate: " + aggregate); return null; }
    }

    // 3. enumerate solution tags on the dataset (the outer selector)
    List<String> solTags = new ArrayList<>();
    try {
      String[] allowed = dset.getAllowedPropertyValues("solution");
      if (allowed != null) {
        for (String s : allowed) if (!"none".equalsIgnoreCase(s)) solTags.add(s);
      }
    } catch (Throwable ignored) {}
    if (solTags.isEmpty()) solTags.add(""); // non-parametric: single run, no solution switching

    BridgeUtil.section("META");
    System.out.println("dataset\t" + dsetTag + "\t" + model.result().dataset(dsetTag).label());
    System.out.println("aggregate\t" + aggregate + "\tftype\t" + ftype + "\tgeomLevel\t" + geomLevel + "\tdim\t" + dim + "\tgeom\t" + geomTag);
    System.out.println("nsoltags\t" + solTags.size());
    System.out.println("expr\t" + expr + "\touter\t" + (outer.isEmpty() ? "(none)" : outer));

    BridgeUtil.section("EVAL");
    System.out.println("soltag\touter\tfreq_Hz\twavenumber_cm-1\tvalue");

    Set<String> seenOuter = new HashSet<>();   // dedup by outer value (string key)
    boolean hasOuter = !outer.isEmpty();
    boolean hasFilter = !outerValueFilter.isEmpty();
    double filterVal = hasFilter ? Double.parseDouble(outerValueFilter) : 0;

    for (String solTag : solTags) {
      if (!solTag.isEmpty()) {
        try { dset.set("solution", solTag); } catch (Throwable ignored) {}
      }
      double[] vals = runAggregate(model, dsetTag, ftype, expr, unit, geomLevel, entIds, needsDenom, geomTag);
      double[] freqs = runGlobal(model, dsetTag, "freq", "Hz");
      double outerVal = hasOuter ? runGlobalFirst(model, dsetTag, outer) : Double.NaN;
      String outerKey = hasOuter ? fmtKey(outerVal) : solTag;

      // outer_value filter: skip curves whose outer doesn't match
      if (hasFilter && !Double.isNaN(outerVal) && Math.abs(outerVal - filterVal) > 1e-9) continue;
      // dedup by outer (keep first non-zero curve per distinct L)
      if (hasOuter) {
        if (seenOuter.contains(outerKey)) continue;
        boolean allZero = true;
        for (double v : vals) if (Math.abs(v) > 1e-30) { allZero = false; break; }
        if (allZero) continue;          // drop degenerate zero solutions (e.g. sol3)
        seenOuter.add(outerKey);
      }

      int n = Math.min(vals.length, freqs.length);
      for (int p = 0; p < n; p++) {
        double fhz = freqs[p];
        double wn = fhz / 29979245800.0;
        System.out.println(solTag + "\t" + (hasOuter ? fmtVal(outerVal) : "") + "\t"
            + fmtVal(fhz) + "\t" + (Double.isNaN(wn) ? "nan" : String.format("%.4f", wn))
            + "\t" + fmtVal(vals[p]));
      }
    }
    return model;
  }

  private static double[] runAggregate(Model m, String dsetTag, String ftype, String expr,
      String unit, int geomLevel, int[] entIds, boolean needsDenom, String geomTag) throws Exception {
    String tag = "agg_" + System.currentTimeMillis();
    try { m.result().numerical().remove(tag); } catch (Throwable ignored) {}
    NumericalFeature nf = m.result().numerical().create(tag, ftype);
    if (entIds.length > 0) {
      nf.selection().geom(geomTag, geomLevel);
      nf.selection().set(entIds);
    }
    if ("IntSurface".equals(ftype)) {
      try { nf.set("intvolume", true); } catch (Throwable ignored) {}
    }
    nf.set("expr", needsDenom ? new String[]{expr, "1"} : new String[]{expr});
    if (unit != null && !unit.isEmpty()) {
      try { nf.setIndex("unit", unit, 0); } catch (Throwable ignored) {}
    }
    try { nf.set("data", dsetTag); } catch (Throwable ignored) {}
    nf.run();
    double[][] re = nf.getReal();
    double[] out;
    if (needsDenom && re.length > 1) {
      out = new double[re[0].length];
      for (int p = 0; p < out.length; p++) {
        double d = re[1][p];
        out[p] = (d == 0) ? Double.NaN : re[0][p] / d;
      }
    } else {
      out = (re.length > 0) ? re[0] : new double[0];
    }
    m.result().numerical().remove(tag);
    return out;
  }

  private static double[] runGlobal(Model m, String dsetTag, String expr, String unit) {
    String tag = "gf_" + System.currentTimeMillis();
    try {
      NumericalFeature gf = m.result().numerical().create(tag, "Global");
      gf.set("expr", new String[]{expr});
      try { gf.setIndex("unit", unit, 0); } catch (Throwable ignored) {}
      try { gf.set("data", dsetTag); } catch (Throwable ignored) {}
      gf.run();
      double[][] re = gf.getReal();
      m.result().numerical().remove(tag);
      return (re.length > 0) ? re[0] : new double[0];
    } catch (Throwable e) { return new double[0]; }
  }

  private static double runGlobalFirst(Model m, String dsetTag, String expr) {
    double[] a = runGlobal(m, dsetTag, expr, "");
    return (a.length > 0) ? a[0] : Double.NaN;
  }

  private static int[] parseInts(String csv) {
    List<Integer> a = new ArrayList<>();
    for (String s : csv.split(",")) { s = s.trim(); if (!s.isEmpty()) a.add(Integer.parseInt(s)); }
    int[] r = new int[a.size()]; for (int i = 0; i < a.size(); i++) r[i] = a.get(i);
    return r;
  }
  private static String fmtVal(double v) { return Double.isNaN(v) ? "nan" : String.format("%.6e", v); }
  private static String fmtKey(double v) { return Double.isNaN(v) ? "nan" : String.format("%.6g", v); }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}