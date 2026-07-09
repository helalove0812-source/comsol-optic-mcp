import com.comsol.model.*;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** Detailed structural dump of a model: geometry features, physics interfaces
 *  + their features (BCs/sources), materials, mesh, studies. Used to learn an
 *  existing model's conventions before reproducing/modifying it.
 *  Input: model=<path>
 */
public class Dump {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String path = in.getProperty("model");
    if (path == null || path.isEmpty()) { BridgeUtil.fail("missing 'model'"); return null; }
    Model model = ModelUtil.load("Model", path);

    String[] ctags = model.component().tags();
    for (String c : ctags) {
      var comp = model.component(c);
      BridgeUtil.section("COMPONENT");
      System.out.println("comp\t" + c + "\tdim=" + comp.geom().tags().length);
      // geometry
      for (String g : comp.geom().tags()) {
        var gf = comp.geom(g);
        System.out.println("geom\t" + g + "\ttype=" + gf.getType());
        try {
          String[] ftags = comp.geom(g).feature().tags();
          for (String ft : ftags) {
            GeomFeature f = comp.geom(g).feature(ft);
            String extra = "";
            for (String prop : new String[]{"size","pos","corner","xmin","xmax","ymin","ymax","width","height"}) {
              try {
                String[] v = f.getStringArray(prop);
                if (v != null && v.length > 0) extra += " " + prop + "=" + Arrays.toString(v);
              } catch (Throwable t) {}
              try {
                String v = f.getString(prop);
                if (v != null && !v.isEmpty()) extra += " " + prop + "=" + v;
              } catch (Throwable t) {}
            }
            System.out.println("  gfeat\t" + ft + "\t" + f.getType() + extra);
          }
        } catch (Throwable t) {}
      }
      // physics
      for (String p : comp.physics().tags()) {
        var ph = comp.physics(p);
        System.out.println("physics\t" + p + "\ttype=" + ph.getType());
        try {
          String[] pftags = comp.physics(p).feature().tags();
          for (String pf : pftags) {
            var f = comp.physics(p).feature(pf);
            String sel = "";
            try { int[] s = f.selection().entities(); sel = "sel=" + Arrays.toString(s); } catch (Throwable t) {}
            String extra = "";
            for (String prop : new String[]{"E0","kdir","kx","ky","kz","H0","Field","Scalar","Input","Slit","Phase","Voltage","Period","kpara","UserDefined","Flux","PortName","lportnum"}) {
              try {
                String v = f.getString(prop);
                if (v != null && !v.isEmpty()) extra += " " + prop + "=" + v;
              } catch (Throwable t) {}
              try {
                String[] v = f.getStringArray(prop);
                if (v != null && v.length > 0) extra += " " + prop + "=" + Arrays.toString(v);
              } catch (Throwable t) {}
            }
            System.out.println("  pfeat\t" + pf + "\t" + f.getType() + "\t" + sel + extra);
          }
        } catch (Throwable t) { System.out.println("  pfeat_err\t" + t.getMessage()); }
      }
      // materials with domain selections
      try {
        for (String m : comp.material().tags()) {
          Material mt = comp.material(m);
          String sel = "";
          try { int[] s = comp.material(m).selection().entities(); sel = "sel=" + Arrays.toString(s); } catch (Throwable t) {}
          System.out.println("material\t" + m + "\t" + mt.label() + "\t" + sel);
          try {
            String[] pgs = mt.propertyGroup().tags();
            for (String pg : pgs) System.out.println("  matgroup\t" + pg);
          } catch (Throwable t) {}
        }
      } catch (Throwable t) {}
      // mesh
      try {
        for (String mh : comp.mesh().tags()) {
          System.out.println("mesh\t" + mh);
          String[] mftags = comp.mesh(mh).feature().tags();
          for (String mf : mftags) System.out.println("  mfeat\t" + mf + "\t" + comp.mesh(mh).feature(mf).getType());
        }
      } catch (Throwable t) {}
    }

    // studies
    try {
      for (String s : model.study().tags()) {
        Study st = model.study(s);
        System.out.println("study\t" + s + "\t" + st.label());
        String[] sftags = st.feature().tags();
        for (String sf : sftags) {
          StudyFeature f = st.feature(sf);
          System.out.println("  sfeat\t" + sf + "\t" + f.getType());
          // dump common frequency/param sweep props
          for (String prop : new String[]{"plist","loclist","outereq","expr","unit","scale","useparam","pname","pval","punit"}) {
            try {
              String[] v = f.getStringArray(prop);
              if (v != null && v.length > 0) System.out.println("    " + prop + "=" + Arrays.toString(v));
            } catch (Throwable t) {}
            try {
              String v = f.getString(prop);
              if (v != null && !v.isEmpty()) System.out.println("    " + prop + "=" + v);
            } catch (Throwable t) {}
          }
        }
      }
    } catch (Throwable t) {}

    return model;
  }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}