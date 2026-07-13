import com.comsol.model.*;
import com.comsol.model.util.ModelUtil;
import java.util.*;
import java.io.*;

/** Generic 2D native-image exporter. Loads a solved .mph, builds a PlotGroup2D
 *  with a Surface plot of <expr> at solution index <solnum>, exports <png>.
 *
 *  Must run under `comsol batch -3drend sw` (software rasterizer) — the default
 *  ogl pipeline native-crashes at "Evaluating 26%" in headless batch mode (the GL
 *  backend is not the variable; xvfb/Mesa and real NVIDIA GLX crash identically).
 *  The MCP run_helper() passes renderer="sw" for this helper.
 *
 *  Capabilities (read via BridgeUtil.loadInput(), all optional except model/png):
 *    model        solved .mph path (required)
 *    png          output png path (required)
 *    solnum       1-based solution index (default 1)
 *    expr         surface expression (default ewfd.normE); use "dom" for a
 *                 per-domain-colored structure plot (no field needed)
 *    descr        expression description (default "Electric field norm")
 *    title        plot title (default "E field")
 *    data         dataset tag (default dset1)
 *    width/height image size px (default 1200/800)
 *    logscale     on/off (default off)
 *    zmin/zmax    color range (optional)
 *    imagetype    bmp/jpeg/png/tiff/gif (default png; raster only, no vector)
 *    exptype      Image2D or Image (default Image2D)
 *  Zoom + z-stretch (the key 2026-07-10 finding — viewscaletype must be MANUAL,
 *  not "none"; "none" is silently overridden by autocontext=autofit so limits
 *  have no effect, "manual" respects them with ~10% auto-padding):
 *    xmin/xmax/ymin/ymax   axis limits (zoom window). autocontext pads ~10%.
 *    viewscaletype         "manual" to engage (only "none"/"manual" accepted)
 *    xscale/yscale          visual z/x ratio (yscale>1 stretches z). NOTE: this is
 *      a z/x RATIO, so a large z range with big yscale makes x auto-expand to
 *      compensate (full 100um stack cannot be z-stretched; use a near-slot
 *      ~2um window). yscale=30 + z-window~1.5um works for slot zoom.
 *  sellist      comma list of domain numbers to restrict the Surface to
 *               (selection-based zoom) — currently throws "Entity has no
 *               selection" on a fresh Surface; left wired but often unused.
 *  Decorations (axes/colorbar/title/grid) are forced on for raster export. */
public class ExportImage {
  public static void main(String[] args) throws Exception {
    Properties in = BridgeUtil.loadInput();
    String path = in.getProperty("model");
    String png = in.getProperty("png");
    String solnum = in.getProperty("solnum", "1");
    String expr = in.getProperty("expr", "ewfd.normE");
    String descr = in.getProperty("descr", "Electric field norm");
    String title = in.getProperty("title", "E field");
    String dset = in.getProperty("data", "dset1");
    String width = in.getProperty("width", "1200");
    String height = in.getProperty("height", "800");
    String logscale = in.getProperty("logscale", "off");
    String zmin = in.getProperty("zmin", null);
    String zmax = in.getProperty("zmax", null);
    String imagetype = in.getProperty("imagetype", "png");
    String exptype = in.getProperty("exptype", "Image2D");
    String xmin = in.getProperty("xmin", null);
    String xmax = in.getProperty("xmax", null);
    String ymin = in.getProperty("ymin", null);
    String ymax = in.getProperty("ymax", null);
    String sellist = in.getProperty("sellist", null);
    String viewscaletype = in.getProperty("viewscaletype", null);
    String xscale = in.getProperty("xscale", null);
    String yscale = in.getProperty("yscale", null);

    Model model = ModelUtil.load("Model", path);
    BridgeUtil.section("PRE");
    System.out.println("model\t" + path);
    System.out.println("png\t" + png);
    System.out.println("solnum\t" + solnum);
    System.out.println("expr\t" + expr);
    try {
      var res = model.result();
      try { res.remove("pgE"); } catch (Throwable e) {}
      try { res.export().remove("img1"); } catch (Throwable e) {}
      res.create("pgE", "PlotGroup2D");
      var pgE = model.result("pgE");
      pgE.set("data", dset);
      pgE.set("titletype", "manual");
      pgE.set("title", title);
      pgE.set("looplevel", new int[]{Integer.parseInt(solnum)});

      boolean wantView = (xmin != null || xmax != null || ymin != null || ymax != null
                          || viewscaletype != null || xscale != null || yscale != null);
      String viewReport = "none";
      if (wantView) {
        try { model.view().remove("vw1"); } catch (Throwable e) {}
        try {
          model.view().create("vw1", 2);
          var vw = model.view("vw1");
          var ax = vw.axis();
          if (xmin != null) try { ax.set("xmin", Double.parseDouble(xmin)); } catch (Throwable e) { System.out.println("xmin_err\t" + e.getClass().getSimpleName()); }
          if (xmax != null) try { ax.set("xmax", Double.parseDouble(xmax)); } catch (Throwable e) { System.out.println("xmax_err\t" + e.getClass().getSimpleName()); }
          if (ymin != null) try { ax.set("ymin", Double.parseDouble(ymin)); } catch (Throwable e) { System.out.println("ymin_err\t" + e.getClass().getSimpleName()); }
          if (ymax != null) try { ax.set("ymax", Double.parseDouble(ymax)); } catch (Throwable e) { System.out.println("ymax_err\t" + e.getClass().getSimpleName()); }
          if (viewscaletype != null) try { ax.set("viewscaletype", viewscaletype); } catch (Throwable e) { System.out.println("viewscaletype_err\t" + e.getClass().getSimpleName() + "\t" + e.getMessage()); }
          if (xscale != null) try { ax.set("xscale", Double.parseDouble(xscale)); } catch (Throwable e) { System.out.println("xscale_err\t" + e.getClass().getSimpleName()); }
          if (yscale != null) try { ax.set("yscale", Double.parseDouble(yscale)); } catch (Throwable e) { System.out.println("yscale_err\t" + e.getClass().getSimpleName()); }
          try { vw.set("locked", "on"); } catch (Throwable e) {}
          pgE.set("view", "vw1");
          pgE.set("ignoreview", "off");
          try { pgE.set("axisactive", "on"); } catch (Throwable e) {}
          viewReport = "xmin=" + xmin + " xmax=" + xmax + " ymin=" + ymin + " ymax=" + ymax
            + " viewscaletype=" + viewscaletype + " xscale=" + xscale + " yscale=" + yscale;
        } catch (Throwable e) { System.out.println("VIEW_ERR\t" + e.getClass().getSimpleName() + "\t" + e.getMessage()); }
      }

      var surf = pgE.feature().create("surf1", "Surface");
      surf.set("expr", new String[]{expr});
      surf.set("descr", new String[]{descr});
      if (logscale.equals("on")) {
        try { surf.set("logscale", "on"); } catch (Throwable e) {}
      }
      if (zmin != null) {
        try { surf.set("min", zmin); surf.set("minactive", "on"); } catch (Throwable e) {}
      }
      if (zmax != null) {
        try { surf.set("max", zmax); surf.set("maxactive", "on"); } catch (Throwable e) {}
      }
      if (sellist != null) {
        try {
          String[] parts = sellist.split(",");
          int[] doms = new int[parts.length];
          for (int i = 0; i < parts.length; i++) doms[i] = Integer.parseInt(parts[i].trim());
          surf.selection().geom(2).set(doms);
          System.out.println("SELLIST_SET\t" + sellist);
        } catch (Throwable e) { System.out.println("SELLIST_ERR\t" + e.getClass().getSimpleName() + "\t" + e.getMessage()); }
      }

      res.export().create("img1", exptype);
      var img = res.export("img1");
      img.set("plotgroup", "pgE");
      img.set("imagetype", imagetype);
      img.set("filename", png);
      if (imagetype.equals("bmp")) img.set("bmpfilename", png);
      else if (imagetype.equals("jpeg")) img.set("jpegfilename", png);
      else if (imagetype.equals("tiff")) img.set("tifffilename", png);
      else if (imagetype.equals("gif")) img.set("giffilename", png);
      else img.set("pngfilename", png);
      img.set("width", width);
      img.set("height", height);
      try { img.set("antialias", "off"); } catch (Throwable e) {}
      try { img.set("qualityactive", "off"); } catch (Throwable e) {}
      try { img.set("highprecisioncolor", "off"); } catch (Throwable e) {}
      // render decorations: axes, colorbar(legend), title, grid
      try { img.set("options2d", "on"); } catch (Throwable e) {}
      try { img.set("axes2d", "on"); } catch (Throwable e) {}
      try { img.set("legend2d", "on"); } catch (Throwable e) {}
      try { img.set("title2d", "on"); } catch (Throwable e) {}
      try { img.set("showgrid", "on"); } catch (Throwable e) {}

      img.run();
      File f = new File(png);
      BridgeUtil.section("EXPORT");
      System.out.println("png\t" + png);
      System.out.println("pngExists\t" + f.exists());
      System.out.println("pngSize\t" + (f.exists() ? f.length() : -1));
      System.out.println("view\t" + viewReport);
      System.out.println("expr\t" + expr);
      System.out.println("solnum\t" + solnum);
    } catch (Throwable t) {
      BridgeUtil.section("ERROR");
      System.out.println("err\t" + t.toString().replace("\n", " | "));
      Throwable c = t; int d = 0;
      while (c != null) {
        System.out.println("depth" + d + "\t" + c.getClass().getName() + "\t" +
          (c.getMessage() == null ? "" : c.getMessage().replace("\n", " | ")));
        c = c.getCause(); d++; if (d > 5) break;
      }
    }
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}