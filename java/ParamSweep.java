import com.comsol.model.*;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** ParamSweep: one-call parametric sweep over a study.
 *
 *  Modes (input `mode` = auto | parametric | iterate, default auto):
 *
 *  - parametric: create a Parametric feature on the study, run once; COMSOL
 *    sweeps all outer values internally and retains a multi-solution dataset
 *    (so eval_aggregate `outer=` can group by value). Best when the study has
 *    NO existing sweep feature.
 *
 *  - iterate: do NOT create a Parametric feature. Loop the values in Java,
 *    `model.param().set(pname, val)` + `study.run()` per value. This avoids the
 *    `Invalid_property_value` conflict that occurs when a Parametric feature
 *    is stacked on top of an existing Frequency/Parametric/Batch sweep (e.g.
 *    the FP_ahn ewfd frequency sweep). Each run rebuilds geometry/mesh and
 *    overwrites the in-memory solution, so by default only the LAST value's
 *    solution is in the saved model — UNLESS `output` contains a `{idx}` or
 *    `{value}` token, in which case a per-value .mph is saved after each run
 *    (token substitution: {idx}=0-based index, {value}=sanitized value expr,
 *    e.g. "6[nm]" -> "6_nm"). One JVM spawn instead of N.
 *
 *  - auto (default): detect existing sweep features on the study (type
 *    contains Freq/Parametric/Batch) -> iterate; otherwise parametric. The
 *    chosen mode is reported in the RUNNING section as `mode\t<mode>`.
 *
 *  Solves mcp-optimization-2026-07-10 #3 (param_sweep usability) and the
 *  2026-07-11 refined-run recurrence (Invalid_property_value on ewfd+freq).
 *
 *  Input:
 *    model=<path to .mph>
 *    study=<tag, default = first study>
 *    pname=<parameter tag, e.g. tAu>
 *    plistarr=<comma-separated values as COMSOL expressions, e.g. "6[nm],10[nm],18[nm]">
 *    mode=<auto|parametric|iterate>  (default auto)
 *    output=<path to save; optional. If it has {idx}/{value} in iterate mode,
 *            one .mph per value is saved.>
 *
 *  Output sections:
 *    PARAM    pname \t unit (best-effort, "" if unitless)
 *    PLIST    value-index \t value-expression
 *    RUNNING  study \t mode \t param_feat (if parametric)
 *    PER_VAL  point \t value \t solve_ms \t has_sol
 *    SAVED    output (path; in iterate per-value mode, one line per value)
 */
public class ParamSweep {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String path = in.getProperty("model");
    if (path == null || path.isEmpty()) { BridgeUtil.fail("missing 'model'"); return null; }
    String studyTag = in.getProperty("study", "").trim();
    String pname = in.getProperty("pname", "").trim();
    String plist = in.getProperty("plistarr", "").trim();
    String output = in.getProperty("output", "").trim();
    String modeReq = in.getProperty("mode", "auto").trim().toLowerCase();
    if (pname.isEmpty()) { BridgeUtil.fail("missing 'pname'"); return null; }
    if (plist.isEmpty()) { BridgeUtil.fail("missing 'plistarr'"); return null; }
    if (!modeReq.equals("auto") && !modeReq.equals("parametric") && !modeReq.equals("iterate")) {
      BridgeUtil.fail("mode must be auto|parametric|iterate, got: " + modeReq); return null;
    }

    Model model = ModelUtil.load("Model", path);

    if (studyTag.isEmpty()) {
      String[] tags = model.study().tags();
      if (tags.length == 0) { BridgeUtil.fail("no studies in model"); return model; }
      studyTag = tags[0];
    }
    Study study = model.study(studyTag);

    String[] values = plist.split(",");
    for (int i = 0; i < values.length; i++) values[i] = values[i].trim();
    if (values.length == 0) { BridgeUtil.fail("plistarr parsed empty"); return model; }

    // ---- mode resolution ----
    String mode = modeReq;
    String existingSweeps = "";
    if (mode.equals("auto")) {
      boolean hasExisting = false;
      StringBuilder sb = new StringBuilder();
      try {
        for (String ft : study.feature().tags()) {
          String ty = featureType(study, ft);
          sb.append(ft).append("=").append(ty).append(",");
          if (ty != null && (ty.contains("Freq") || ty.contains("Parametric")
              || ty.contains("Batch"))) {
            hasExisting = true;
          }
        }
      } catch (Throwable t) { /* ignore — fall through to parametric */ }
      existingSweeps = sb.toString();
      mode = hasExisting ? "iterate" : "parametric";
    }

    // emit PARAM + PLIST metadata
    BridgeUtil.section("PARAM");
    String firstVal = values[0];
    String unit = "";
    int lb = firstVal.indexOf('[');
    int rb = firstVal.indexOf(']', lb + 1);
    if (lb >= 0 && rb > lb) unit = firstVal.substring(lb + 1, rb);
    System.out.println("pname\t" + pname + "\tunit\t" + unit);

    BridgeUtil.section("PLIST");
    System.out.println("index\tvalue");
    for (int i = 0; i < values.length; i++) System.out.println(i + "\t" + values[i]);

    if (mode.equals("iterate")) {
      return runIterate(model, study, studyTag, pname, values, output, existingSweeps);
    } else {
      return runParametric(model, study, studyTag, pname, values, output, existingSweeps);
    }
  }

  /** Best-effort Feature.getType() with reflection fallback. */
  private static String featureType(Study study, String ft) {
    try {
      Object feat = study.feature(ft);
      try { return (String) feat.getClass().getMethod("getType").invoke(feat); }
      catch (Throwable t) { return "?"; }
    } catch (Throwable t) { return "?"; }
  }

  // ---------------------------------------------------------------------
  // iterate: no Parametric feature, loop in Java
  // ---------------------------------------------------------------------
  private static Model runIterate(Model model, Study study, String studyTag,
      String pname, String[] values, String output, String existingSweeps) throws Exception {
    boolean perValue = output.contains("{idx}") || output.contains("{value}");
    BridgeUtil.section("RUNNING");
    System.out.println("study\t" + studyTag);
    System.out.println("mode\titerate");
    if (!existingSweeps.isEmpty()) System.out.println("existing_sweeps\t" + existingSweeps);
    long total0 = System.currentTimeMillis();
    long[] perMs = new long[values.length];
    for (int i = 0; i < values.length; i++) {
      try {
        model.param().set(pname, values[i]);
      } catch (Throwable t) {
        BridgeUtil.fail("param.set(" + pname + "=" + values[i] + ") failed: " + t.getMessage());
        return model;
      }
      long t0 = System.currentTimeMillis();
      try {
        study.run();   // rebuilds geometry/mesh + solves (existing freq sweep runs per value)
      } catch (Throwable t) {
        BridgeUtil.fail("iterate study.run() failed at value " + values[i] + ": " + t.getMessage());
        return model;
      }
      perMs[i] = System.currentTimeMillis() - t0;
      // optional per-value save
      if (perValue && !output.isEmpty()) {
        String p = templated(output, i, values[i]);
        try { model.save(p); } catch (Throwable t) {
          BridgeUtil.fail("save failed at value " + values[i] + " -> " + p + ": " + t.getMessage());
          return model;
        }
      }
    }
    long total = System.currentTimeMillis() - total0;
    System.out.println("total_ms\t" + total);
    System.out.println("per_value_ms\t" + (values.length > 0 ? (total / values.length) : total));

    BridgeUtil.section("PER_VAL");
    System.out.println("index\tvalue\tsolve_ms\thas_sol");
    for (int i = 0; i < values.length; i++) {
      System.out.println(i + "\t" + values[i] + "\t" + perMs[i] + "\t1");
    }

    if (!output.isEmpty()) {
      if (perValue) {
        BridgeUtil.section("SAVED");
        for (int i = 0; i < values.length; i++)
          System.out.println(i + "\t" + templated(output, i, values[i]));
      } else {
        // single save = last value's solution
        try { model.save(output); } catch (Throwable t) {
          BridgeUtil.fail("save failed: " + t.getMessage()); return model;
        }
        BridgeUtil.section("SAVED");
        System.out.println("output\t" + output);
      }
    }
    return model;
  }

  // ---------------------------------------------------------------------
  // parametric: create Parametric feature, run once (retains multi-sol dataset)
  // ---------------------------------------------------------------------
  private static Model runParametric(Model model, Study study, String studyTag,
      String pname, String[] values, String output, String existingSweeps) throws Exception {
    String paramFeatTag = "param_" + pname;
    try {
      try { study.feature().remove(paramFeatTag); } catch (Throwable ignored) {}
      study.feature().create(paramFeatTag, "Parametric");
      var pf = study.feature(paramFeatTag);
      // COMSOL API: plistarr is String[][] — outer dim = #param lists, inner = values.
      String[][] plistArr = new String[][]{ values };
      pf.set("pname", new String[]{pname});
      pf.set("plistarr", plistArr);
      pf.set("punit", new String[]{""});
    } catch (Throwable t) {
      BridgeUtil.fail("create Parametric feature failed: " + t.getMessage()
          + " — try mode=iterate to avoid conflicts with existing sweeps");
      return model;
    }

    BridgeUtil.section("RUNNING");
    System.out.println("study\t" + studyTag);
    System.out.println("mode\tparametric");
    System.out.println("param_feat\t" + paramFeatTag);
    if (!existingSweeps.isEmpty()) System.out.println("existing_sweeps\t" + existingSweeps);
    long t0 = System.currentTimeMillis();
    try {
      study.run();
    } catch (Throwable t) {
      BridgeUtil.fail("parametric study.run() failed: " + t.getMessage()
          + " — this typically happens when a Parametric feature is stacked on an"
          + " existing frequency/parametric sweep. Retry with mode=iterate.");
      return model;
    }
    long dt = System.currentTimeMillis() - t0;
    System.out.println("total_ms\t" + dt);
    System.out.println("per_value_ms\t" + (values.length > 0 ? (dt / values.length) : dt));

    // count retained outer solutions in the parametric dataset
    BridgeUtil.section("PER_VAL");
    System.out.println("index\tvalue\tsolve_ms\thas_sol");
    int nsols = 0;
    try {
      for (String d : model.result().dataset().tags()) {
        if (model.result().dataset(d).label().toLowerCase().contains("parametric")) {
          String[] allowed = model.result().dataset(d).getAllowedPropertyValues("solution");
          if (allowed != null) {
            for (String s : allowed) if (!"none".equalsIgnoreCase(s)) nsols++;
          }
          break;
        }
      }
    } catch (Throwable t) {
      System.out.println("dataset_probe_err\t" + t.getMessage());
    }
    int nReport = Math.max(values.length, nsols);
    for (int i = 0; i < nReport; i++) {
      String valExpr = (i < values.length) ? values[i] : "";
      System.out.println(i + "\t" + valExpr + "\t" + dt + "\t" + (i < nsols ? "1" : "?"));
    }

    if (!output.isEmpty()) {
      model.save(output);
      BridgeUtil.section("SAVED");
      System.out.println("output\t" + output);
    }
    return model;
  }

  /** Substitute {idx} (0-based) and {value} (sanitized, "6[nm]"->"6_nm"). */
  private static String templated(String tmpl, int idx, String value) {
    String safe = value.replaceAll("[\\[\\] ]", "_").replaceAll("[^A-Za-z0-9_.\\-]", "_");
    return tmpl.replace("{idx}", Integer.toString(idx))
               .replace("{value}", safe);
  }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}