import com.comsol.model.Model;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** Debug helper: run a study and print the FULL exception chain + any solver/study
 *  warnings, so "The following feature has encountered a problem" can be attributed
 *  to a specific feature. Usage: props model=<path> study=<tag>. */
public class DebugRun {
  public static void main(String[] args) throws Exception {
    Properties in = BridgeUtil.loadInput();
    String mp = in.getProperty("model");
    String study = in.getProperty("study", "std1");
    Model model = ModelUtil.load("Model", mp);
    // optionally remove params that may collide with COMSOL built-ins (e.g. w=ang.freq)
    String rm = in.getProperty("rmparam", "");
    for (String p : rm.split(",")) {
      p = p.trim();
      if (p.isEmpty()) continue;
      try { model.param().remove(p); System.out.println("RMPARAM\t" + p + "\tOK"); }
      catch (Throwable e) { System.out.println("RMPARAM\t" + p + "\tFAIL " + e.getMessage()); }
    }
    // optionally remove a stale solver sequence (sol tag) before run
    String rmsol = in.getProperty("rmsol", "");
    for (String s : rmsol.split(",")) {
      s = s.trim();
      if (s.isEmpty()) continue;
      try { model.sol().remove(s); System.out.println("RMSOL\t" + s + "\tOK"); }
      catch (Throwable e) { System.out.println("RMSOL\t" + s + "\tFAIL " + e.getMessage()); }
    }
    try {
      model.study(study).run();
      System.out.println("SOLVE_OK");
    } catch (Throwable e) {
      System.out.println("EXC: " + e.getClass().getName() + ": " + e.getMessage());
      // FlException.getMessages() returns the full error message tree (with feature names)
      try {
        Object msgs = ((com.comsol.util.exceptions.FlException) e).getMessages();
        System.out.println("MSG_TOSTRING: " + msgs);
        // reflectively extract every element (FlStringList) — holds the var name
        try {
          Class<?> mc = msgs.getClass();
          System.out.println("MSG_CLASS: " + mc.getName());
          for (java.lang.reflect.Method m : mc.getMethods()) {
            String mn = m.getName();
            if (mn.equals("size") && m.getParameterCount()==0) {
              int n = (int) m.invoke(msgs); System.out.println("MSG_SIZE: " + n);
              java.lang.reflect.Method gm = null;
              for (java.lang.reflect.Method mm : mc.getMethods())
                if (mm.getName().equals("get") && mm.getParameterCount()==1) { gm = mm; break; }
              if (gm != null) for (int i=0;i<n;i++) System.out.println("MSG[" + i + "]: " + gm.invoke(msgs, i));
            }
          }
          for (java.lang.reflect.Method m : mc.getMethods())
            if (m.getName().equals("toArray") && m.getParameterCount()==0) {
              Object[] a = (Object[]) m.invoke(msgs);
              for (int i=0;i<a.length;i++) System.out.println("MSG_ARR[" + i + "]: " + a[i]);
            }
        } catch (Throwable re) { System.out.println("MSG_REFLECT_ERR: " + re); }
      } catch (Throwable me) { System.out.println("nomsgs: " + me.getMessage()); }
      Throwable c = e.getCause();
      while (c != null) {
        System.out.println("CAUSE: " + c.getClass().getName() + ": " + c.getMessage());
        try {
          Object msgs = ((com.comsol.util.exceptions.FlException) c).getMessages();
          System.out.println("CMSG: " + msgs);
        } catch (Throwable me) {}
        c = c.getCause();
      }
      for (StackTraceElement s : e.getStackTrace()) System.out.println("  at " + s);
      // enumerate physics features + selections for sanity
      try {
        var ewfd = model.component("comp1").physics("ewfd");
        for (String ft : ewfd.feature().tags()) {
          int[] sel = ewfd.feature(ft).selection().entities();
          System.out.println("PFEAT\t" + ft + "\t" + ewfd.feature(ft).label() + "\tsel=" + Arrays.toString(sel));
        }
      } catch (Throwable ee) { System.out.println("nofeat: " + ee.getMessage()); }
    }
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}