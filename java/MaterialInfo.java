import com.comsol.model.*;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** MaterialInfo: enumerate a model's materials, their propertyGroups, and the
 *  GROUP IDENTIFIER (the string you pass to propertyGroup(identifier) and
 *  material().propertyGroup(identifier).set(...)). Solves the "rg1 vs rfi"
 *  footgun in COMSOL MCP build_model: a freshly-created RefractiveIndex group
 *  has identifier = its tag (e.g. "rg1"), not the canonical name "rfi" that
 *  ewfd looks up. The fix is `material.duplicate(matX, refMat)`, which
 *  preserves the original identifier; this tool reveals which one to use.
 *
 *  Input:
 *    model=<path>
 *    material=<tag>  (optional; empty = all materials)
 *
 *  Output sections:
 *    MATERIALS     list of {comp, tag, label, ngroups}
 *    MATGROUP_<tag>  for each material: one row per propertyGroup with
 *                    groupname \t identifier \t <name=value pairs>
 */
public class MaterialInfo {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String path = in.getProperty("model");
    if (path == null || path.isEmpty()) { BridgeUtil.fail("missing 'model'"); return null; }
    String wantTag = in.getProperty("material", "").trim();
    Model model = ModelUtil.load("Model", path);

    BridgeUtil.section("MATERIALS");
    System.out.println("comp\ttag\tlabel\tngroups\tidentifier_format");
    int foundAny = 0;
    for (String c : model.component().tags()) {
      var comp = model.component(c);
      for (String mtag : comp.material().tags()) {
        if (!wantTag.isEmpty() && !mtag.equals(wantTag)) continue;
        Material mat = comp.material(mtag);
        int ngroups = 0;
        String idfmt = "?";
        try {
          String[] pgs = mat.propertyGroup().tags();
          ngroups = pgs.length;
          if (pgs.length > 0) {
            // quick sanity: identifier of the first group, name of the first group
            String firstIdent = "?";
            try { firstIdent = mat.propertyGroup(pgs[0]).identifier(); } catch (Throwable t) {}
            idfmt = pgs[0] + " -> " + firstIdent;
          }
        } catch (Throwable t) {}
        System.out.println(c + "\t" + mtag + "\t" + mat.label() + "\t" + ngroups + "\t" + idfmt);
        foundAny++;

        // Per-material section: enumerate propertyGroup with identifier + dump simple values
        BridgeUtil.section("MATGROUP_" + c + "_" + mtag);
        System.out.println("groupname\tidentifier");
        try {
          String[] pgs = mat.propertyGroup().tags();
          for (String pg : pgs) {
            var group = mat.propertyGroup(pg);
            String ident = "?";
            try { ident = group.identifier(); } catch (Throwable t) {}
            System.out.println(pg + "\t" + ident);
            // Try to dump simple values for the group's properties (best-effort).
            // COMSOL's MaterialModel doesn't expose propertyNames() / get(String)
            // across all versions, so we probe a known list of common properties
            // (n/k for RefractiveIndex, rho/cp/k for Basic, etc.) and report
            // whichever ones the group accepts.
            String[] COMMON = {
                "n", "k", "ni", "ki",        // RefractiveIndex
                "rho", "cp", "k_iso", "E", "nu", // Basic / Solid
                "sigma", "epsilonr",         // Electric
                "mur"                        // Magnetic
            };
            for (String pn : COMMON) {
                String val = null;
                String kind = "string";
                try {
                    val = group.getString(pn);
                } catch (Throwable t1) {
                    try {
                        String[] sa = group.getStringArray(pn);
                        if (sa != null) { val = Arrays.toString(sa); kind = "stringarray"; }
                    } catch (Throwable t2) {
                        try {
                            double[] da = group.getDoubleArray(pn);
                            if (da != null) { val = Arrays.toString(da); kind = "doublearray"; }
                        } catch (Throwable t3) {}
                    }
                }
                if (val != null && !val.isEmpty() && !val.equals("0") && !val.equals("0.0")) {
                    System.out.println("  prop\t" + pn + "\t" + kind + "\t" + val);
                }
            }
          }
        } catch (Throwable t) {
          System.out.println("  group_err\t" + t.getMessage());
        }
      }
    }
    if (foundAny == 0) {
      System.out.println("(no materials matched" + (wantTag.isEmpty() ? ")" : " filter='" + wantTag + "')"));
    }
    return model;
  }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}
