import com.comsol.model.*;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** Parameterized API probe. Discover valid property names for a geometry
 *  feature or a physics interface on the fly — used to draft correct build Java.
 *
 *  Input props:
 *    kind=geom|physics   (default geom)
 *    dim=2|3             (default 2)
 *    type=<feature or interface type, e.g. Rectangle or ElectricCurrents>
 *    props=<comma-separated candidate property names to test>
 */
public class Probe {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String kind = in.getProperty("kind", "geom");
    int dim = Integer.parseInt(in.getProperty("dim", "2"));
    String type = in.getProperty("type");
    if (type == null) { BridgeUtil.fail("missing 'type'"); return null; }
    String[] props = in.getProperty("props", "").split(",");
    for (int i = 0; i < props.length; i++) props[i] = props[i].trim();

    Model model = ModelUtil.create("Model");
    model.component().create("comp1", true);
    model.component("comp1").geom().create("geom1", dim);
    // a base domain so physics has something to attach to
    if (dim == 2) {
      GeomFeature r = model.component("comp1").geom("geom1").create("r1", "Rectangle");
      r.set("size", new String[]{"1","1"});
    } else {
      GeomFeature b = model.component("comp1").geom("geom1").create("b1", "Block");
      b.set("size", new String[]{"1","1","1"});
    }
    model.component("comp1").geom("geom1").run();

    if (kind.equals("physics")) {
      model.component("comp1").physics().create("p1", type, "geom1");
      String[] ftags = model.component("comp1").physics("p1").feature().tags();
      BridgeUtil.section("PHYSICS_FEATURES");
      for (String ft : ftags) {
        var pf = model.component("comp1").physics("p1").feature(ft);
        System.out.println("feat\t" + ft + "\t" + pf.getType());
      }
      // Optional: create a named extra feature (featType) inside this interface
      // and test candidate props on IT instead of the first default feature.
      String featType = in.getProperty("feat_type");
      Object target = null;
      if (featType != null && !featType.isEmpty()) {
        try {
          var nf = model.component("comp1").physics("p1").feature().create("probe1", featType);
          BridgeUtil.section("CREATED_FEAT");
          System.out.println("type\t" + nf.getType());
          target = nf;
        } catch (Throwable t) {
          BridgeUtil.section("CREATED_FEAT");
          System.out.println("error\t" + t.getMessage());
        }
      }
      BridgeUtil.section("PROP_PROBE");
      if (target != null) {
        testProps(target, props);
      } else if (ftags.length > 0) {
        testProps(model.component("comp1").physics("p1").feature(ftags[0]), props);
      }
    } else {
      GeomFeature f = model.component("comp1").geom("geom1").create("f1", type);
      BridgeUtil.section("CREATED");
      System.out.println("type\t" + f.getType());
      BridgeUtil.section("PROP_PROBE");
      testProps(f, props);
    }
    return model;
  }

  static void testProps(Object f, String[] props) throws Exception {
    java.lang.reflect.Method setM = f.getClass().getMethod("set", String.class, String.class);
    java.lang.reflect.Method setArrM = null;
    try { setArrM = f.getClass().getMethod("set", String.class, String[].class); }
    catch (NoSuchMethodException e) { /* fine */ }
    java.lang.reflect.Method allowedM = null;
    try { allowedM = f.getClass().getMethod("getAllowedPropertyValues", String.class); }
    catch (NoSuchMethodException e) { /* fine */ }
    for (String p : props) {
      if (p.isEmpty()) continue;
      String ok = "no", vals = "";
      // first, see if the prop has enumerated allowed values (selector props);
      // setting the literal "1" on those throws, so use the first allowed value.
      String[] allowed = null;
      if (allowedM != null) {
        try {
          Object av = allowedM.invoke(f, p);
          if (av instanceof String[]) allowed = (String[]) av;
        } catch (Throwable t) { /* not enumerable */ }
      }
      String testVal = (allowed != null && allowed.length > 0) ? allowed[0] : "1";
      String[] testArr = (allowed != null && allowed.length > 0) ? new String[]{allowed[0],allowed[0],allowed[0]} : new String[]{"1","1","1"};
      try { setM.invoke(f, p, testVal); ok = "ok"; } catch (Throwable t) { ok = "no"; }
      if (ok.equals("no") && setArrM != null) {
        try { setArrM.invoke(f, p, testArr); ok = "vec"; } catch (Throwable t) { ok = "no"; }
      }
      if (allowed != null && allowed.length > 0)
        vals = "\tallowed=" + Arrays.toString(allowed);
      System.out.println(ok + "\t" + p + vals);
    }
  }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}