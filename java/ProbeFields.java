import com.comsol.model.Model;
import com.comsol.model.NumericalFeature;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** Probe what's defined where on a 1D ewfd model. Try a battery of field
 *  expressions in the liquid domain and report which compute successfully.
 */
public class ProbeFields {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    Model model = ModelUtil.load("Model", in.getProperty("model"));
    int[] liqDom = new int[]{Integer.parseInt(in.getProperty("liq", "2"))};

    String[] exprs = {
      "ewfd.normE^2",
      "ewfd.normE",
      "ewfd.Ez",
      "ewfd.relE",
      "ewfd.relEz",
      "ewfd.normErel^2",
      "ewfd.normErel",
      "ewfd.Weav",
      "ewfd.Wmav",
      "ewfd.dWe",
      "ewfd.dWe/(0.5*epsilon0_const*epsSi*(1[V/m])^2*(L1+L2)*hSi)",
      "ewfd.Ebgz",
    };
    BridgeUtil.section("PROBE");
    for (String e : exprs) {
      try {
        String tag = "pp_" + System.currentTimeMillis() + "_" + Math.abs(e.hashCode());
        NumericalFeature nf = model.result().numerical().create(tag, "IntSurface");
        nf.set("expr", new String[]{e});
        nf.selection().set(liqDom);
        nf.run();
        double[][] re = nf.getReal();
        double v = (re.length > 0 && re[0].length > 0) ? re[0][0] : Double.NaN;
        System.out.println("ok\t" + e + "\t" + v);
        model.result().numerical().remove(tag);
      } catch (Throwable t) {
        System.out.println("err\t" + e + "\t" + t.getMessage());
      }
    }
    return model;
  }
  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}
