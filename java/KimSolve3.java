import com.comsol.model.*;
import com.comsol.model.util.ModelUtil;
import java.util.*;
import java.lang.reflect.*;

/** Run std1; on failure, reflectively dump EVERY String-returning no-arg method
 *  on the exception + its cause chain, so the real COMSOL feature tag / entity /
 *  error key behind "Error in multiphysics compilation" is revealed. */
public class KimSolve3 {
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
        System.out.println("--- depth" + depth + " class=" + c.getClass().getName() + " ---");
        // basic
        System.out.println("getMessage\t" + safe(c.getMessage()));
        System.out.println("getLocalizedMessage\t" + safe(c.getLocalizedMessage()));
        System.out.println("toString\t" + safe(c.toString()));
        // reflectively call every no-arg method returning String/Object on this exception
        try {
          Method[] ms = c.getClass().getMethods();
          for (Method m : ms) {
            if (m.getParameterCount() != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt.equals(String.class) || rt.equals(Object.class)) {
              String name = m.getName();
              // skip noisy Object methods
              if (name.equals("toString") || name.equals("getMessage")
                  || name.equals("getLocalizedMessage") || name.equals("getClass")) continue;
              try {
                Object r = m.invoke(c);
                String rv = (r == null) ? "(null)" : r.toString();
                // truncate huge
                if (rv.length() > 500) rv = rv.substring(0, 500) + "...[trunc]";
                System.out.println("M\t" + name + "\t" + rv);
              } catch (Throwable e) { /* ignore */ }
            }
          }
        } catch (Throwable e) { System.out.println("reflect_err\t" + e.getMessage()); }
        // stack (all frames)
        StackTraceElement[] st = c.getStackTrace();
        for (int i = 0; i < st.length; i++) System.out.println("st\t" + st[i]);
        c = c.getCause();
        depth++;
        if (depth > 6) break;
      }
    }
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }

  static String safe(String s) { return s == null ? "(null)" : s.replace("\n", " | "); }
}