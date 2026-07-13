import com.comsol.model.*;
import com.comsol.model.util.ModelUtil;
import java.util.*;
import java.io.*;

/** 3D-model native-image exporter for the Proscia MIM L-cavity.
 *  batch blocks: dataset creation, geometry-selection create/resolve, and
 *  plot-feature selection setting (.geom().set() / .set() / Hide all fail with
 *  "cannot be created in this context" / "Entity has no selection"). What IS
 *  allowed: creating plot groups & plot features, setting their non-selection
 *  properties, and exporting a 3D Image. So we use a Slice plot feature
 *  (quickplane + quickz level) to cut a horizontal plane through the cavity
 *  without needing a cut-plane dataset or a boundary selection.
 *    plotype=slice  : Slice at (quickplane, quickz) -> shows field in the
 *                     IPDip spacer plane (L-shaped hot-spot under the patch).
 *                     expr=ewfd.normE for field, expr=dom for structure.
 *    plotype=surface: Surface on ALL boundaries (no selection) -> full box;
 *                     use only when occlusion is acceptable.
 *  Must run under `comsol batch -3drend sw`.
 *  Props: model, png, plotype(slice|surface), expr, descr, title, solnum,
 *         logscale, zmin, zmax, width, height, quickplane(xy|xz|yz),
 *         quickz(level), solvefreqs(THz csv, optional), view(top|side|iso). */
public class ExportCutPlane {
  public static void main(String[] args) throws Exception {
    Properties in = BridgeUtil.loadInput();
    String path = in.getProperty("model");
    String png = in.getProperty("png");
    String plotype = in.getProperty("plotype", "slice");
    String expr = in.getProperty("expr", "ewfd.normE");
    String descr = in.getProperty("descr", "Electric field norm");
    String title = in.getProperty("title", "E field");
    String solnum = in.getProperty("solnum", "1");
    String width = in.getProperty("width", "1100");
    String height = in.getProperty("height", "860");
    String logscale = in.getProperty("logscale", "off");
    String zmin = in.getProperty("zmin", null);
    String zmax = in.getProperty("zmax", null);
    String solvefreqs = in.getProperty("solvefreqs", "");
    String view = in.getProperty("view", "iso");
    String quickplane = in.getProperty("quickplane", "xy");
    String quickz = in.getProperty("quickz", "0.225");
    String quickn = in.getProperty("quickn", "1");   // number of slices

    Model model = ModelUtil.load("Model", path);
    BridgeUtil.section("PRE");
    System.out.println("model\t"+path+" plotype\t"+plotype+" expr\t"+expr+" solnum\t"+solnum+" view\t"+view+" qp\t"+quickplane+" qz\t"+quickz);
    try {
      var res = model.result();
      if (solvefreqs != null && solvefreqs.length() > 0) {
        String[] fs = solvefreqs.split(",");
        String[] fwith = new String[fs.length];
        for (int i=0;i<fs.length;i++) fwith[i] = fs[i].trim() + "[THz]";
        model.study("std1").feature("freq").set("plist", fwith);
        model.study("std1").run();
        System.out.println("solved\t" + fs.length + " freqs");
      }
      try { res.remove("pgE"); } catch (Throwable e) {}
      try { res.export().remove("img1"); } catch (Throwable e) {}
      res.create("pgE", "PlotGroup3D");
      var pgE = model.result("pgE");
      pgE.set("data", "dset1");
      pgE.set("titletype", "manual");
      pgE.set("title", title);
      pgE.set("looplevel", new int[]{Integer.parseInt(solnum)});
      try { pgE.set("showlegends","on"); } catch (Throwable e) {}
      try { pgE.set("axisactive","on"); } catch (Throwable e) {}

      if (view.equals("top") || view.equals("side")) {
        try {
          try { model.view().remove("vw3"); } catch (Throwable e) {}
          model.view().create("vw3", 3);
          var vw = model.view("vw3");
          var cam = vw.camera();
          if (view.equals("top")) {
            cam.set("position", new double[]{0, 0, 4});
            cam.set("target", new double[]{0, 0, 0.3});
            cam.set("up", new double[]{0, 1, 0});
            cam.set("projection", "orthographic");
          } else {
            cam.set("position", new double[]{4, 0, 0.3});
            cam.set("target", new double[]{0, 0, 0.3});
            cam.set("up", new double[]{0, 0, 1});
            cam.set("projection", "orthographic");
          }
          try { vw.set("locked","on"); } catch (Throwable e) {}
          pgE.set("view","vw3");
          pgE.set("ignoreview","off");
        } catch (Throwable e) { System.out.println("VIEW_ERR\t"+e.getClass().getSimpleName()+"\t"+e.getMessage()); }
      }

      if (plotype.equals("slice")) {
        var sl = pgE.feature().create("sl1", "Slice");
        sl.set("expr", new String[]{expr});
        sl.set("descr", new String[]{descr});
        try { sl.set("quickplane", quickplane); } catch (Throwable e) { System.out.println("QP_ERR\t"+e.getMessage()); }
        // axis-specific coord + slice-count props (discovered via .properties())
        String coordProp, countProp;
        if (quickplane.equals("xy")) { coordProp="quickz"; countProp="quickznumber"; }
        else if (quickplane.equals("xz")) { coordProp="quicky"; countProp="quickynumber"; }
        else { coordProp="quickx"; countProp="quickxnumber"; }
        try { sl.set(coordProp, quickz); } catch (Throwable e) { System.out.println("QCOORD_ERR\t"+e.getMessage()); }
        try { sl.set(countProp, Integer.parseInt(quickn)); System.out.println("QCOUNT_set\t"+countProp+"="+quickn); }
        catch (Throwable e) { System.out.println("QCOUNT_ERR\t"+e.getClass().getSimpleName()+"\t"+e.getMessage()); }
        try { sl.set("colorlegend","on"); } catch (Throwable e) {}
        try { sl.set("showcolor","on"); } catch (Throwable e) {}
        if (logscale.equals("on")) try { sl.set("logscale","on"); } catch (Throwable e) {}
        if (zmin!=null) try { sl.set("rangecolormin",zmin); sl.set("rangecoloractive","on"); } catch (Throwable e) {}
        if (zmax!=null) try { sl.set("rangecolormax",zmax); sl.set("rangecoloractive","on"); } catch (Throwable e) {}
        System.out.println("SLICE_SET");
      } else {
        var surf = pgE.feature().create("surf1", "Surface");
        surf.set("expr", new String[]{expr});
        surf.set("descr", new String[]{descr});
        if (logscale.equals("on")) try { surf.set("logscale","on"); } catch (Throwable e) {}
        if (zmin!=null) try { surf.set("min",zmin); surf.set("minactive","on"); } catch (Throwable e) {}
        if (zmax!=null) try { surf.set("max",zmax); surf.set("maxactive","on"); } catch (Throwable e) {}
        System.out.println("SURFACE_SET");
      }

      res.export().create("img1", "Image");
      var img = res.export("img1");
      img.set("plotgroup","pgE");
      img.set("imagetype", "png");
      img.set("filename", png);
      img.set("pngfilename", png);
      img.set("width", width);
      img.set("height", height);
      try { img.set("antialias","on"); } catch (Throwable e) {}
      try { img.set("options3d","on"); } catch (Throwable e) {}
      try { img.set("title3d","on"); } catch (Throwable e) {}
      try { img.set("legend3d","on"); } catch (Throwable e) {}
      try { img.set("axes3d","on"); } catch (Throwable e) {}
      try { img.set("showgrid","on"); } catch (Throwable e) {}
      try { img.set("logo3d","off"); } catch (Throwable e) {}
      img.run();
      File f = new File(png);
      BridgeUtil.section("EXPORT");
      System.out.println("png\t"+png);
      System.out.println("pngExists\t"+f.exists());
      System.out.println("pngSize\t"+(f.exists()?f.length():-1));
    } catch (Throwable t) {
      BridgeUtil.section("ERROR");
      System.out.println("err\t"+t.toString().replace("\n"," | "));
      Throwable c=t; int d=0;
      while (c!=null) { System.out.println("depth"+d+"\t"+c.getClass().getName()+"\t"+(c.getMessage()==null?"":c.getMessage().replace("\n"," | "))); c=c.getCause(); d++; if(d>5) break; }
    }
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}