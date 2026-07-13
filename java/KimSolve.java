import com.comsol.model.*;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** Run std1 on a model and print the FULL error (exception class + message + cause
 *  chain) so the COMSOL "feature has encountered a problem" headline is expanded
 *  with the actual feature tag + reason. */
public class KimSolve {
  public static void main(String[] args) throws Exception {
    Properties in = BridgeUtil.loadInput();
    String path = in.getProperty("model");
    Model model = ModelUtil.load("Model", path);
    String study = in.getProperty("study", "std1");
    BridgeUtil.section("PRE");
    System.out.println("model\t" + path);
    System.out.println("study\t" + study);
    try {
      model.study(study).run();
      BridgeUtil.section("OK");
      System.out.println("solved_ok");
    } catch (Throwable t) {
      BridgeUtil.section("SOLVE_ERROR");
      Throwable c = t;
      int depth = 0;
      while (c != null) {
        System.out.println("depth" + depth + "_class\t" + c.getClass().getName());
        String msg = c.getMessage();
        System.out.println("depth" + depth + "_msg\t" + (msg == null ? "(null)" : msg.replace("\n", " | ")));
        // Stack trace (first 8 frames)
        StackTraceElement[] st = c.getStackTrace();
        for (int i = 0; i < Math.min(st.length, 8); i++) {
          System.out.println("depth" + depth + "_st\t" + st[i]);
        }
        c = c.getCause();
        depth++;
        if (depth > 6) break;
      }
    }
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}