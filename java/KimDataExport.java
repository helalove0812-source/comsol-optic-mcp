import com.comsol.model.*;
import com.comsol.model.util.ModelUtil;
import java.util.*;
import java.io.*;

/** GL-free spatial field export: evaluate <expr> at all mesh nodes of <dset>
 *  at solution index <solnum>, write x,y,<expr> as TSV to <out> via COMSOL's
 *  "Data" export (text, no OpenGL). Run via comsol batch (no xvfb needed).
 *  Props: model, out, solnum(1-based), expr(default ewfd.normE), data(dset1). */
public class KimDataExport {
  public static void main(String[] args) throws Exception {
    Properties in = BridgeUtil.loadInput();
    String path = in.getProperty("model");
    String out = in.getProperty("out");
    String solnum = in.getProperty("solnum", "1");
    String expr = in.getProperty("expr", "ewfd.normE");
    String dset = in.getProperty("data", "dset1");
    Model model = ModelUtil.load("Model", path);
    BridgeUtil.section("PRE");
    System.out.println("model\t" + path);
    System.out.println("out\t" + out);
    System.out.println("solnum\t" + solnum);
    System.out.println("expr\t" + expr);
    try {
      var res = model.result();
      try { res.remove("pgE"); } catch (Throwable e) {}
      try { res.export().remove("d1"); } catch (Throwable e) {}
      res.create("pgE", "PlotGroup2D");
      model.result("pgE").set("data", dset);
      model.result("pgE").set("looplevel", new int[]{Integer.parseInt(solnum)});
      var surf = model.result("pgE").feature().create("surf1", "Surface");
      surf.set("expr", new String[]{expr});
      // Data export (text) — no GL rasterization
      res.export().create("d1", "Data");
      var d = res.export("d1");
      d.set("data", dset);
      d.set("expr", new String[]{expr});
      d.set("filename", out);
      d.set("solnum", solnum);
      try { d.set("includecoords", "on"); } catch (Throwable e) {}
      try { d.set("showheader", "on"); } catch (Throwable e) {}
      d.run();
      File f = new File(out);
      BridgeUtil.section("EXPORT");
      System.out.println("exists\t" + f.exists());
      System.out.println("size\t" + (f.exists() ? f.length() : -1));
    } catch (Throwable t) {
      BridgeUtil.section("EXPORT_ERROR");
      System.out.println("err\t" + t.toString().replace("\n", " | "));
      Throwable c = t; int dd = 0;
      while (c != null) {
        System.out.println("depth" + dd + "\t" + c.getClass().getName() + "\t" +
          (c.getMessage() == null ? "" : c.getMessage().replace("\n", " | ")));
        c = c.getCause(); dd++; if (dd > 5) break;
      }
    }
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}