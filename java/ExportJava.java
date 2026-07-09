import com.comsol.model.Model;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** Export a model to Java source (COMSOL 'Export Model to Java'). */
public class ExportJava {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String input = in.getProperty("model");
    String output = in.getProperty("output");
    if (input == null || output == null) { BridgeUtil.fail("need 'model' and 'output'"); return null; }
    Model model = ModelUtil.load("Model", input);
    model.save(output);   // .java extension => COMSOL exports Java source
    BridgeUtil.section("EXPORTED");
    System.out.println("output\t" + output);
    return model;
  }
  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}