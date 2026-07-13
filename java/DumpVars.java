import com.comsol.model.Model;
import com.comsol.model.util.ModelUtil;
import java.util.*;
import java.lang.reflect.*;

/** Dump all variable-declaration sources via reflection (COMSOL Variable API differs
 *  across versions; avoid guessing). Prints methods of comp.variable() so the real
 *  API is visible, then enumerates variable groups + their (name, expr) entries, and
 *  ewfd physics features. Goal: attribute "Duplicate_variable_name" at compile. */
public class DumpVars {
  static void showMethods(String tag, Object o) {
    if (o == null) { System.out.println(tag + " = null"); return; }
    Class<?> c = o.getClass();
    System.out.println("=== " + tag + " : " + c.getName() + " ===");
    for (Method m : c.getMethods()) {
      String mn = m.getName();
      if ((mn.startsWith("get") || mn.equals("tags") || mn.equals("name") || mn.equals("expr")
           || mn.equals("variable") || mn.equals("label") || mn.equals("size") || mn.equals("toArray"))
          && m.getParameterCount() <= 1)
        System.out.println("  M\t" + mn + "(" + m.getParameterCount() + ") -> " + m.getReturnType().getSimpleName());
    }
  }
  public static void main(String[] args) throws Exception {
    Properties in = BridgeUtil.loadInput();
    Model model = ModelUtil.load("Model", in.getProperty("model"));
    var comp = model.component("comp1");

    System.out.println("=== PARAMS ===");
    for (String p : model.param().varnames())
      System.out.println("PARAM\t" + p + "\t=" + model.param().get(p));

    Object vroot = null;
    try { vroot = comp.variable(); } catch (Throwable e) { System.out.println("comp.variable() err: " + e); }
    if (vroot != null) {
      showMethods("comp.variable()", vroot);
      try {
        Method tags = vroot.getClass().getMethod("tags");
        Object tagsRes = tags.invoke(vroot);
        String[] tarr = (tagsRes instanceof String[]) ? (String[]) tagsRes : null;
        System.out.println("VARTAGS: " + (tarr==null? tagsRes : Arrays.toString(tarr)));
        if (tarr != null) for (String vt : tarr) {
          System.out.println("--- vartag " + vt + " ---");
          // fetch the sub-feature: try variable(String), get(String)
          Object vf = null;
          for (String mn : new String[]{"variable","get"}) {
            try { Method g = vroot.getClass().getMethod(mn, String.class); vf = g.invoke(vroot, vt); break; }
            catch (NoSuchMethodException ns) {}
          }
          if (vf == null) { System.out.println("  (no fetch method)"); continue; }
          showMethods("varfeat "+vt, vf);
          // try to list entries: name() / getNames() returning String[] or array
          for (String mn : new String[]{"name","getNames","varNames","variables"}) {
            try {
              Method nm = vf.getClass().getMethod(mn);
              Object nr = nm.invoke(vf);
              if (nr instanceof String[]) { for (String s : (String[])nr) System.out.println("  VN\t" + s); }
              else if (nr instanceof Object[]) { for (Object s : (Object[])nr) System.out.println("  VN\t" + s); }
              else System.out.println("  " + mn + " -> " + nr);
            } catch (NoSuchMethodException ns) {}
          }
        }
      } catch (Throwable e) { System.out.println("vartags err: " + e); }
    }

    System.out.println("=== PHYSICS ewfd ===");
    try {
      var ewfd = comp.physics("ewfd");
      for (String ft : ewfd.feature().tags()) {
        var f = ewfd.feature(ft);
        System.out.println("PFEAT\t" + ft + "\t" + f.label());
      }
    } catch (Throwable e) { System.out.println("ewfd err: " + e); }

    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}