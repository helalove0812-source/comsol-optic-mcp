import com.comsol.model.Model;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** Raw COMSOL physics-feature API pass-through, so the MCP can set ANY feature
 *  property (e.g. port1.set("E0i","0,0,1")) without the wrapper knowing the
 *  name in advance. This bypasses the "未知参数" errors you hit when a generic
 *  helper hard-codes only known properties.
 *
 *  Supports five actions on a physics interface's features, plus interface-level
 *  property setting:
 *    action=create      -> physics.feature().create(feat_tag, feat_type); apply props
 *    action=update      -> physics.feature(feat_tag); apply props
 *    action=delete      -> physics.feature().remove(feat_tag)
 *    action=setselection-> physics.feature(feat_tag).selection().geom("geom1",dim).set(domains)
 *    action=list        -> print all feature tags+types of the physics
 *    target=interface   -> apply props to physics(tag) itself (e.g. wavelength)
 *
 *  Props are applied best-effort with type fallback: scalar(String) -> array
 *  (String[], comma-split) -> int -> double. Each prop reports ok/err so you
 *  can see exactly which property name COMSOL rejected (then use probe_feature
 *  to discover the right name).
 *
 *  Input props:
 *    model, output (optional save path),
 *    physics=<tag e.g. ewfd>, action, target (feature|interface, default feature),
 *    feat_type=<e.g. Port>, feat_tag=<e.g. port1>,
 *    selection=<csv domains>, sel_dim=<geom level int>,
 *    prop.0.name / prop.0.value / prop.0.kind (scalar|array|int|double|bool, default scalar), ...
 */
public class PhysicsFeature {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String input = in.getProperty("model");
    if (input == null) { BridgeUtil.fail("missing 'model'"); return null; }
    String output = in.getProperty("output", "");
    String physicsTag = in.getProperty("physics", "");
    if (physicsTag.isEmpty()) { BridgeUtil.fail("missing 'physics'"); return null; }
    String action = in.getProperty("action", "list").toLowerCase();
    String target = in.getProperty("target", "feature").toLowerCase();
    String featType = in.getProperty("feat_type", "");
    String featTag = in.getProperty("feat_tag", "");

    Model model = ModelUtil.load("Model", input);
    var physics = model.physics(physicsTag);

    BridgeUtil.section("ACTION");
    System.out.println("physics\t" + physicsTag + "\taction\t" + action + "\ttarget\t" + target);

    if (action.equals("list")) {
      String[] tags = physics.feature().tags();
      System.out.println("nfeatures\t" + tags.length);
      for (String t : tags) {
        String ty = "?";
        try { ty = physics.feature(t).getType(); } catch (Throwable e) { ty = "(?)"; }
        System.out.println("feat\t" + t + "\t" + ty);
      }
    } else if (target.equals("interface")) {
      // apply props directly to the physics interface
      applyProps(physics, in, "INTERFACE");
    } else {
      // feature-level
      Object feat = null;
      if (action.equals("create")) {
        if (featType.isEmpty()) { BridgeUtil.fail("create needs feat_type"); return null; }
        if (featTag.isEmpty()) featTag = "feat" + System.currentTimeMillis();
        try { physics.feature().create(featTag, featType); }
        catch (Throwable e) { BridgeUtil.fail("create " + featType + " failed: " + e.getMessage()); return null; }
        feat = physics.feature(featTag);
      } else if (action.equals("delete")) {
        if (featTag.isEmpty()) { BridgeUtil.fail("delete needs feat_tag"); return null; }
        try { physics.feature().remove(featTag); System.out.println("deleted\t" + featTag); }
        catch (Throwable e) { BridgeUtil.fail("delete failed: " + e.getMessage()); return null; }
      } else {
        if (featTag.isEmpty()) { BridgeUtil.fail(action + " needs feat_tag"); return null; }
        feat = physics.feature(featTag);
      }
      if (feat != null) {
        applyProps(feat, in, "FEAT");
        // selection (reflective: feat.selection().geom("geom1", dim).set(dom))
        String sel = in.getProperty("selection", "");
        if (!sel.isEmpty()) {
          int dim = Integer.parseInt(in.getProperty("sel_dim", "2"));
          int[] dom = parseInts(sel);
          try {
            Object selObj = feat.getClass().getMethod("selection").invoke(feat);
            selObj.getClass().getMethod("geom", String.class, int.class).invoke(selObj, "geom1", dim);
            selObj.getClass().getMethod("set", int[].class).invoke(selObj, new Object[]{dom});
            System.out.println("selection\t" + sel + "\tdim\t" + dim + "\tOK");
          } catch (Throwable e) {
            System.out.println("selection\t" + sel + "\tERR " + e.getMessage());
          }
        }
      }
    }

    if (!output.isEmpty()) {
      model.save(output);
      BridgeUtil.section("SAVED");
      System.out.println("path\t" + output);
    } else {
      BridgeUtil.section("NOTSAVED");
      System.out.println("note\toutput not given; changes are NOT persisted");
    }
    return model;
  }

  /** Apply prop.N.* to any feature/physics object that has set(String,...). */
  private static void applyProps(Object feat, Properties in, String section) {
    java.util.List<String[]> report = new ArrayList<>();
    for (int i = 0; ; i++) {
      String name = in.getProperty("prop." + i + ".name", "");
      if (name.isEmpty()) break;
      String val = in.getProperty("prop." + i + ".value", "");
      String kind = in.getProperty("prop." + i + ".kind", "scalar").toLowerCase();
      String result = setProp(feat, name, val, kind);
      report.add(new String[]{name, val, kind, result});
    }
    BridgeUtil.section(section + "_PROPS");
    System.out.println("name\tvalue\tkind\tresult");
    for (String[] r : report) System.out.println(r[0] + "\t" + r[1] + "\t" + r[2] + "\t" + r[3]);
  }

  private static String setProp(Object feat, String name, String val, String kind) {
    try {
      switch (kind) {
        case "array":  invokeSet(feat, name, new String[]{val}); return "ok_array"; // single-element array
        case "int":    invokeSet(feat, name, new int[]{Integer.parseInt(val)}); return "ok_int";
        case "double": invokeSetD(feat, name, Double.parseDouble(val)); return "ok_double";
        case "bool":   invokeSet(feat, name, Boolean.parseBoolean(val)); return "ok_bool";
        default: // scalar: try String, then comma-split array, then int, then double
          try { invokeSet(feat, name, val); return "ok_scalar"; }
          catch (Throwable e1) {
            if (val.contains(",")) {
              try { invokeSet(feat, name, val.split(",")); return "ok_array_split"; }
              catch (Throwable ignored2) {}
            }
            try { invokeSet(feat, name, new int[]{Integer.parseInt(val)}); return "ok_int_fallback"; }
            catch (Throwable ignored3) {}
            try { invokeSetD(feat, name, Double.parseDouble(val)); return "ok_double_fallback"; }
            catch (Throwable ignored4) {}
            return "ERR " + errStr(e1);
          }
      }
    } catch (Throwable e) { return "ERR " + errStr(e); }
  }

  private static String errStr(Throwable e) {
    String m = e.getMessage();
    return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
  }

  // Reflective set to avoid depending on a specific feature interface type.
  private static void invokeSet(Object feat, String name, Object value) throws Throwable {
    java.lang.reflect.Method m = null;
    Class<?> vCls = value.getClass();
    // prefer set(String, String)/set(String, String[])/set(String, int[])/set(String, boolean)
    for (Class<?> c : new Class[]{value.getClass(), String.class, String[].class, int[].class, boolean.class, int.class}) {
      try { m = feat.getClass().getMethod("set", String.class, c); break; } catch (NoSuchMethodException ignored) {}
    }
    if (m == null) throw new RuntimeException("no set method");
    m.invoke(feat, name, value);
  }
  private static void invokeSetD(Object feat, String name, double v) throws Throwable {
    java.lang.reflect.Method m = null;
    for (Class<?> c : new Class[]{double.class, double[].class}) {
      try { m = feat.getClass().getMethod("set", String.class, c); break; } catch (NoSuchMethodException ignored) {}
    }
    if (m == null) throw new RuntimeException("no set(double) method");
    if (m.getParameterTypes()[1] == double[].class) m.invoke(feat, name, new Object[]{new double[]{v}});
    else m.invoke(feat, name, v);
  }

  private static int[] parseInts(String csv) {
    List<Integer> a = new ArrayList<>();
    for (String s : csv.split(",")) { s = s.trim(); if (!s.isEmpty()) a.add(Integer.parseInt(s)); }
    int[] r = new int[a.size()]; for (int i = 0; i < a.size(); i++) r[i] = a.get(i);
    return r;
  }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}