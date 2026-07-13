import com.comsol.model.*;
import com.comsol.model.util.ModelUtil;
import java.util.*;
import java.io.*;

/** Load a solved .mph, render a 2D Surface plot of <expr> at solution index
 *  <solnum>, export to <png>. Intended to run under xvfb-run so the headless
 *  Image export has an OpenGL context. Reads props via BridgeUtil.loadInput():
 *   model, png, solnum(1-based), expr(default ewfd.normE), title, data(dset1),
 *   width(1200), height(800), logscale(on/off, default off), zmin, zmax. */
public class KimExport {
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
    // NEW: restrict Surface to a domain selection (selection-based zoom — auto-fit
    // then hugs the selected domains instead of the full geometry). Comma list.
    String sellist = in.getProperty("sellist", null);
    // NEW: z-stretch via View2D manual scale (probe viewscaletype=manual + xscale/yscale)
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
      // probe which result export types exist
      BridgeUtil.section("EXPORT_TYPES");
      String[] types = {"Image","PlotImage","Graphics","EPS","PostScript","Vector","PlotPNG","PlotEPS","PDF","PlotPDF","Image2D","Mesh","Table","Animation","PlotGroup2D","PlotGroup3D"};
      for (String t : types) {
        try {
          res.export().create("probe_" + t, t);
          String[] ps = res.export("probe_" + t).properties();
          System.out.println("TYPE_OK\t" + t + "\t" + java.util.Arrays.toString(ps).substring(0, Math.min(300, java.util.Arrays.toString(ps).length())));
          res.export().remove("probe_" + t);
        } catch (Throwable e) {
          System.out.println("TYPE_NO\t" + t + "\t" + e.getClass().getSimpleName());
        }
      }
      try { res.remove("pgE"); } catch (Throwable e) {}
      try { res.export().remove("img1"); } catch (Throwable e) {}
      res.create("pgE", "PlotGroup2D");
      var pgE = model.result("pgE");
      pgE.set("data", dset);
      pgE.set("titletype", "manual");
      pgE.set("title", title);
      pgE.set("looplevel", new int[]{Integer.parseInt(solnum)});
      // apply a View2D for axis limits (zoom) and/or manual scale (z-stretch).
      boolean wantView = (xmin != null || xmax != null || ymin != null || ymax != null
                          || viewscaletype != null || xscale != null || yscale != null);
      if (wantView) {
        try { var pax = pgE.axis(); System.out.println("PGAXIS_PROPS\t" + java.util.Arrays.toString(pax.properties())); } catch (Throwable e) { System.out.println("PGAXIS_NONE\t" + e.getClass().getSimpleName()); }
        try { model.view().remove("vw1"); } catch (Throwable e) {}
        try {
          model.view().create("vw1", 2);
          var vw = model.view("vw1");
          var ax = vw.axis();
          // dump ALL axis property names + allowed values for the scale/limit ones
          String[] axp = ax.properties();
          System.out.println("VWAXIS_PROPS\t" + java.util.Arrays.toString(axp));
          if (xmin != null) try { ax.set("xmin", Double.parseDouble(xmin)); } catch (Throwable e) { System.out.println("xmin_err\t" + e.getClass().getSimpleName()); }
          if (xmax != null) try { ax.set("xmax", Double.parseDouble(xmax)); } catch (Throwable e) { System.out.println("xmax_err\t" + e.getClass().getSimpleName()); }
          if (ymin != null) try { ax.set("ymin", Double.parseDouble(ymin)); } catch (Throwable e) { System.out.println("ymin_err\t" + e.getClass().getSimpleName()); }
          if (ymax != null) try { ax.set("ymax", Double.parseDouble(ymax)); } catch (Throwable e) { System.out.println("ymax_err\t" + e.getClass().getSimpleName()); }
          if (viewscaletype != null) try { ax.set("viewscaletype", viewscaletype); System.out.println("viewscaletype_set\t" + viewscaletype); } catch (Throwable e) { System.out.println("viewscaletype_err\t" + e.getClass().getSimpleName() + "\t" + e.getMessage()); }
          if (xscale != null) try { ax.set("xscale", Double.parseDouble(xscale)); System.out.println("xscale_set\t" + xscale); } catch (Throwable e) { System.out.println("xscale_err\t" + e.getClass().getSimpleName() + "\t" + e.getMessage()); }
          if (yscale != null) try { ax.set("yscale", Double.parseDouble(yscale)); System.out.println("yscale_set\t" + yscale); } catch (Throwable e) { System.out.println("yscale_err\t" + e.getClass().getSimpleName() + "\t" + e.getMessage()); }
          try { vw.set("locked", "on"); } catch (Throwable e) { System.out.println("locked_err\t" + e.getClass().getSimpleName()); }
          pgE.set("view", "vw1");
          pgE.set("ignoreview", "off");
          try { pgE.set("axisactive", "on"); } catch (Throwable e) { System.out.println("axisactive_err\t" + e.getClass().getSimpleName()); }
          System.out.println("VIEW_SET\t" + xmin + "\t" + xmax + "\t" + ymin + "\t" + ymax + "\t" + viewscaletype + "\t" + xscale + "\t" + yscale);
        } catch (Throwable e) { System.out.println("VIEW_ERR\t" + e.getClass().getSimpleName() + "\t" + e.getMessage()); }
      }
      var surf = pgE.feature().create("surf1", "Surface");
      surf.set("expr", new String[]{expr});
      surf.set("descr", new String[]{descr});
      if (sellist != null) {
        try {
          String[] parts = sellist.split(",");
          int[] doms = new int[parts.length];
          for (int i = 0; i < parts.length; i++) doms[i] = Integer.parseInt(parts[i].trim());
          surf.selection().geom(2).set(doms);
          System.out.println("SELLIST_SET\t" + sellist + "\t" + doms.length + " domains");
        } catch (Throwable e) { System.out.println("SELLIST_ERR\t" + e.getClass().getSimpleName() + "\t" + e.getMessage()); }
      }
      if (logscale.equals("on")) {
        try { surf.set("logscale", "on"); } catch (Throwable e) {}
      }
      if (zmin != null) {
        try { surf.set("min", zmin); surf.set("minactive", "on"); } catch (Throwable e) {}
      }
      if (zmax != null) {
        try { surf.set("max", zmax); surf.set("maxactive", "on"); } catch (Throwable e) {}
      }
      res.export().create("img1", exptype);
      var img = res.export("img1");
      img.set("plotgroup", "pgE");
      img.set("imagetype", imagetype);
      // set type-specific filename + generic filename
      img.set("filename", png);
      if (imagetype.equals("eps")) img.set("epsfilename", png);
      else if (imagetype.equals("bmp")) img.set("bmpfilename", png);
      else if (imagetype.equals("jpeg")) img.set("jpegfilename", png);
      else if (imagetype.equals("tiff")) img.set("tifffilename", png);
      else if (imagetype.equals("gif")) img.set("giffilename", png);
      else img.set("pngfilename", png);
      img.set("width", width);
      img.set("height", height);
      try { img.set("antialias", "off"); } catch (Throwable e) { System.out.println("setantialias_err\t" + e.getClass().getSimpleName()); }
      try { img.set("qualityactive", "off"); } catch (Throwable e) {}
      try { img.set("highprecisioncolor", "off"); } catch (Throwable e) {}
      // render decorations: axes, colorbar(legend), title, grid
      try { img.set("options2d", "on"); } catch (Throwable e) {}
      try { img.set("axes2d", "on"); } catch (Throwable e) {}
      try { img.set("legend2d", "on"); } catch (Throwable e) {}
      try { img.set("title2d", "on"); } catch (Throwable e) {}
      try { img.set("showgrid", "on"); } catch (Throwable e) {}
      BridgeUtil.section("BEFORE_RUN");
      System.out.println("exptype\t" + exptype + "\timagetype\t" + imagetype);
      img.run();
      File f = new File(png);
      BridgeUtil.section("EXPORT");
      System.out.println("pngExists\t" + f.exists());
      System.out.println("pngSize\t" + (f.exists() ? f.length() : -1));
    } catch (Throwable t) {
      BridgeUtil.section("EXPORT_ERROR");
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