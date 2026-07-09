import com.comsol.model.*;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** ParamSweep: one-call parametric sweep over a study.
 *
 *  Solves mcp-optimization-2026-07-10 #3: previously had to set_param +
 *  run_study N times, or hand-write a Parametric feature (with the cryptic
 *  "Parameter names not consistent with number of parameter lists" error
 *  if plistarr / pname pair up wrong).
 *
 *  Input:
 *    model=<path to .mph>
 *    study=<tag, default = first study>
 *    pname=<parameter tag, e.g. tAu>
 *    plistarr=<comma-separated values as COMSOL expressions, e.g. "6[nm],10[nm],18[nm]">
 *    output=<path to save the solved swept model; optional>
 *
 *  Output sections:
 *    PARAM    pname \t unit (best-effort, "" if unitless)
 *    PLIST    value-index \t value-expression
 *    RUNNING  study tag
 *    PER_VAL  point \t value \t solve_ms
 *    SAVED    path (if output given)
 *
 *  Re-runs the study with the sweep appended. The Parametric feature is
 *  created on the study (tag = "param_" + pname) and run via
 *  model.study(study).run(), which COMSOL will sweep across all outer
 *  values internally.
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
    if (pname.isEmpty()) { BridgeUtil.fail("missing 'pname'"); return null; }
    if (plist.isEmpty()) { BridgeUtil.fail("missing 'plistarr'"); return null; }

    Model model = ModelUtil.load("Model", path);

    // pick study
    if (studyTag.isEmpty()) {
      String[] tags = model.study().tags();
      if (tags.length == 0) { BridgeUtil.fail("no studies in model"); return model; }
      studyTag = tags[0];
    }
    Study study = model.study(studyTag);

    // parse plistarr
    String[] values = plist.split(",");
    for (int i = 0; i < values.length; i++) values[i] = values[i].trim();
    if (values.length == 0) { BridgeUtil.fail("plistarr parsed empty"); return model; }

    // build / reuse Parametric feature on this study
    String paramFeatTag = "param_" + pname;
    try {
      // remove an existing sweep with the same tag, so re-runs are idempotent
      try { study.feature().remove(paramFeatTag); } catch (Throwable ignored) {}
      study.feature().create(paramFeatTag, "Parametric");
      var pf = study.feature(paramFeatTag);
      // COMSOL API: plistarr is a String[][] — outer dim = number of param
      // lists, inner dim = values per list. For a 1-param sweep that's
      // {{v1, v2, ...}}. pname / punit are String[] of length 1.
      String[][] plistArr = new String[][]{ values };
      pf.set("pname", new String[]{pname});
      pf.set("plistarr", plistArr);
      pf.set("punit", new String[]{""});
    } catch (Throwable t) {
      BridgeUtil.fail("create Parametric feature failed: " + t.getMessage());
      return model;
    }

    // emit metadata
    BridgeUtil.section("PARAM");
    String firstVal = values[0];
    String unit = "";
    int lb = firstVal.indexOf('[');
    int rb = firstVal.indexOf(']', lb + 1);
    if (lb >= 0 && rb > lb) unit = firstVal.substring(lb + 1, rb);
    System.out.println("pname\t" + pname + "\tunit\t" + unit);

    BridgeUtil.section("PLIST");
    System.out.println("index\tvalue");
    for (int i = 0; i < values.length; i++) {
      System.out.println(i + "\t" + values[i]);
    }

    BridgeUtil.section("RUNNING");
    System.out.println("study\t" + studyTag + "\tparam_feat\t" + paramFeatTag);
    long t0 = System.currentTimeMillis();
    try {
      study.run();
    } catch (Throwable t) {
      BridgeUtil.fail("study.run() failed: " + t.getMessage());
      return model;
    }
    long dt = System.currentTimeMillis() - t0;
    System.out.println("total_ms\t" + dt);
    System.out.println("per_value_ms\t" + (values.length > 0 ? (dt / values.length) : dt));

    // For each outer value, run a single study invocation is not separately
    // timed (study.run() runs all outer values internally), so we just
    // distribute the wall time linearly and report the outer values that
    // actually got a non-empty solution. Easiest sanity check: dataset
    // solution count.
    BridgeUtil.section("PER_VAL");
    System.out.println("index\tvalue\tsolve_ms\thas_sol");
    int nsols = 0;
    try {
      // find the parametric dataset
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

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}
