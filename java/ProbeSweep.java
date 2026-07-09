import com.comsol.model.Model;
import com.comsol.model.NumericalFeature;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** Probe v6: confirm the recipe end-to-end on the IntSurface aggregate we
 *  actually use. For each solution tag of the parametric dataset, set
 *  dset.solution=<tag>, run (a) Global eval of L, (b) Global eval of freq,
 *  (c) IntSurface of ewfd.normE^2 over the liquid domain. Print per-tag L,
 *  npoints (freq), and the integral value at a couple of freq indices, to
 *  confirm each tag = full freq curve and that sol1(L0.9) != sol2(L0.78).
 *
 *  Input props:
 *    model=<path to solved .mph>
 *    outer=<swept param name, e.g. L>
 *    liq_dom=<csv domain nums, e.g. 2>
 */
public class ProbeSweep {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String input = in.getProperty("model");
    if (input == null) { BridgeUtil.fail("missing 'model'"); return null; }
    String outer = in.getProperty("outer", "L");
    int[] liq = parseInts(in.getProperty("liq_dom", "2"));
    Model model = ModelUtil.load("Model", input);

    String paramTag = null;
    for (String t : model.result().dataset().tags()) {
      if (model.result().dataset(t).label().toLowerCase().contains("parametric")) { paramTag = t; break; }
    }
    Object dset = model.result().dataset(paramTag);
    String[] sols = (String[]) dset.getClass().getMethod("getAllowedPropertyValues", String.class).invoke(dset, "solution");

    BridgeUtil.section("PER_SOLTAG");
    System.out.println("param_dset\t" + paramTag + "\tnsols\t" + sols.length);
    System.out.println("soltag\tL\tnfreq\tinteg[0]\tinteg[60]\tinteg[120]");
    for (String s : sols) {
      if (s.equals("none")) continue;
      try {
        dset.getClass().getMethod("set", String.class, String.class).invoke(dset, "solution", s);
      } catch (Throwable e) { System.out.println(s + "\tSET_ERR"); continue; }
      double L = globalFirst(model, paramTag, outer);
      double[] fr = globalAll(model, paramTag, "freq");
      double[] it = intSurfaceAll(model, paramTag, "ewfd.normE^2", liq);
      int nf = fr.length;
      String i0 = it.length>0 ? fmt(it[0]) : "nan";
      String i60 = it.length>60 ? fmt(it[60]) : "nan";
      String i120 = it.length>120 ? fmt(it[120]) : "nan";
      System.out.println(s + "\t" + fmt(L) + "\t" + nf + "\t" + i0 + "\t" + i60 + "\t" + i120);
    }
    return model;
  }

  private static double globalFirst(Model m, String d, String expr) throws Exception {
    String tag = "gf_" + System.currentTimeMillis();
    NumericalFeature gf = m.result().numerical().create(tag, "Global");
    gf.set("expr", new String[]{expr});
    try { gf.set("data", d); } catch (Throwable ignored) {}
    gf.run();
    double[][] re = gf.getReal();
    m.result().numerical().remove(tag);
    return (re.length>0 && re[0].length>0) ? re[0][0] : Double.NaN;
  }
  private static double[] globalAll(Model m, String d, String expr) throws Exception {
    String tag = "ga_" + System.currentTimeMillis();
    NumericalFeature gf = m.result().numerical().create(tag, "Global");
    gf.set("expr", new String[]{expr});
    try { gf.setIndex("unit","Hz",0); } catch (Throwable ignored) {}
    try { gf.set("data", d); } catch (Throwable ignored) {}
    gf.run();
    double[][] re = gf.getReal();
    m.result().numerical().remove(tag);
    return (re.length>0) ? re[0] : new double[0];
  }
  private static double[] intSurfaceAll(Model m, String d, String expr, int[] dom) throws Exception {
    String tag = "is_" + System.currentTimeMillis();
    NumericalFeature nf = m.result().numerical().create(tag, "IntSurface");
    nf.set("expr", new String[]{expr});
    if (dom.length>0) { nf.selection().geom("geom1",2); nf.selection().set(dom); }
    try { nf.set("intvolume", true); } catch (Throwable ignored) {}
    try { nf.set("data", d); } catch (Throwable ignored) {}
    nf.run();
    double[][] re = nf.getReal();
    m.result().numerical().remove(tag);
    return (re.length>0) ? re[0] : new double[0];
  }
  private static String fmt(double v){ return Double.isNaN(v)?"nan":String.format("%.6e",v); }
  private static int[] parseInts(String csv){ List<Integer> a=new ArrayList<>(); for(String s:csv.split(",")){s=s.trim(); if(!s.isEmpty())a.add(Integer.parseInt(s));} int[] r=new int[a.size()]; for(int i=0;i<a.size();i++)r[i]=a.get(i); return r; }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}