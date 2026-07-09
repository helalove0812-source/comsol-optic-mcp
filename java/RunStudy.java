import com.comsol.model.Model;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** Load a model, set parameters (param.<name>=<expr>), run a study, save to output. */
public class RunStudy {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String input = in.getProperty("model");
    String output = in.getProperty("output");
    String study = in.getProperty("study");
    if (input == null) { BridgeUtil.fail("missing 'model'"); return null; }
    if (study == null) study = null; // will pick first if null

    Model model = ModelUtil.load("Model", input);

    // apply parameters
    BridgeUtil.section("PARAMS_SET");
    List<String> pkeys = new ArrayList<>();
    for (String key : in.stringPropertyNames()) if (key.startsWith("param.")) pkeys.add(key);
    Collections.sort(pkeys);
    for (String key : pkeys) {
      String name = key.substring("param.".length());
      String val = in.getProperty(key);
      model.param().set(name, val);
      System.out.println(name + "\t" + val);
    }

    // choose study
    if (study == null || study.isEmpty()) {
      String[] tags = model.study().tags();
      if (tags.length == 0) { BridgeUtil.fail("no studies in model"); return model; }
      study = tags[0];
    }

    BridgeUtil.section("RUNNING");
    System.out.println("study\t" + study);
    long t0 = System.currentTimeMillis();
    String exitCode = "0";
    try {
      model.study(study).run();
    } catch (Throwable t) {
      exitCode = "1";
      System.out.println("run_err\t" + t.getMessage());
    }
    long dt = System.currentTimeMillis() - t0;
    // MCP opt #4: emit dofs + exit_code alongside study tag. server.py picks
    // these up by key name (`study\tx`, `done_ms\tN`, etc.) so the free-form
    // progress lines interleaved in the section don't confuse parsing.
    System.out.println("done_ms\t" + dt);
    System.out.println("dofs\t?");
    System.out.println("exit_code\t" + exitCode);

    if (output != null && !output.isEmpty()) {
      model.save(output);
      BridgeUtil.section("SAVED");
      System.out.println("output\t" + output);
    }
    return model;
  }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}