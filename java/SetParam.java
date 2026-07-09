import com.comsol.model.Model;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** Set one or more model parameters and (optionally) save the model WITHOUT
 *  re-solving. This is the lightweight param-edit tool: it lets the user tweak
 *  geometry/symbolic params (e.g. L, W, wn) and persist the .mph so a later
 *  run_study / build_model can pick up the new values.
 *
 *  IMPORTANT: changing a param on a SOLVED model does NOT change the already-
 *  computed fields. To get new physics results for the new param value, follow
 *  up with run_study (which sets params AND re-solves). Use SetParam when you
 *  only want to edit + save (e.g. prepare a sweep, update geometry before
 *  meshing), not to recompute results.
 *
 *  Input props:
 *    model=<path to .mph>
 *    output=<path to save the edited .mph; if omitted, changes are NOT saved>
 *    param.0.name / param.0.value, param.1.name / param.1.value, ...
 *  Output section PARAMS: name\told_value\tnew_value for each set param.
 */
public class SetParam {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String input = in.getProperty("model");
    if (input == null) { BridgeUtil.fail("missing 'model'"); return null; }
    String output = in.getProperty("output", "");
    Model model = ModelUtil.load("Model", input);

    List<String[]> changes = new ArrayList<>(); // {name, oldVal, newVal}
    for (int i = 0; ; i++) {
      String name = in.getProperty("param." + i + ".name", "");
      if (name.isEmpty()) break;
      String val = in.getProperty("param." + i + ".value", "");
      String old = "";
      try { old = model.param().get(name); } catch (Throwable e) { old = "(undefined)"; }
      model.param().set(name, val);
      changes.add(new String[]{name, old, val});
    }
    if (changes.isEmpty()) { BridgeUtil.fail("no param.*.name given"); return null; }

    BridgeUtil.section("PARAMS");
    System.out.println("name\told\tnew");
    for (String[] c : changes) System.out.println(c[0] + "\t" + c[1] + "\t" + c[2]);

    if (!output.isEmpty()) {
      model.save(output);
      BridgeUtil.section("SAVED");
      System.out.println("path\t" + output);
    } else {
      BridgeUtil.section("NOTSAVED");
      System.out.println("note\toutput path not given; changes are NOT persisted");
    }
    return model;
  }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}