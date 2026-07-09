import java.io.*;
import java.util.*;

/** Shared helpers for the COMSOL MCP bridge helper classes. */
public class BridgeUtil {
  /** Load input properties from the path in env COMSOL_BRIDGE_INPUT (UTF-8). */
  static Properties loadInput() throws IOException {
    Properties props = new Properties();
    String p = System.getenv("COMSOL_BRIDGE_INPUT");
    if (p != null && p.length() > 0) {
      try (BufferedReader r = new BufferedReader(
             new InputStreamReader(new FileInputStream(p), "UTF-8"))) {
        props.load(r);
      }
    }
    return props;
  }

  /** Emit a section marker the Python server parses. */
  static void section(String name) {
    System.out.println("<<<" + name + ">>>");
    System.out.flush();
  }

  /** Emit the end-of-output marker. */
  static void end() {
    System.out.println("<<<END>>>");
    System.out.flush();
  }

  /** Print an error section with a message, then end. */
  static void fail(String msg) {
    section("ERROR");
    System.out.println(msg);
    end();
  }

  // ---------------------------------------------------------------------
  // MCP optimization: explicit-error helpers (added 2026-07-10, plan #1)
  // ---------------------------------------------------------------------

  /** Run r, but if it throws, write a structured <<<ERROR>>> section and
   *  re-throw. Use this in user body code to surface silent try/catch loss.
   *
   *  Example:
   *    BridgeUtil.tryStmt("create mat5.RefractiveIndex", () -> {
   *      model.component("comp1").material("mat5")
   *           .propertyGroup().create("rg1", "RefractiveIndex");
   *    });
   */
  static void tryStmt(String desc, Runnable r) {
    try {
      r.run();
    } catch (Throwable t) {
      String msg = (t.getMessage() == null || t.getMessage().isEmpty())
          ? t.getClass().getSimpleName()
          : t.getMessage();
      // single-line ERROR record; the Python server collects it into java_warnings
      System.out.println("[WARN] " + desc + " -> " + t.getClass().getName() + ": " + msg);
      System.out.flush();
      // re-throw so the rest of the body aborts — silent skip is the bug we're fixing
      throw new RuntimeException(desc + ": " + msg, t);
    }
  }

  /** Remove a selection tag from every entity-typed container in a model, so
   *  a fresh selection().create(tag, ...) doesn't trip "Tag X already exists".
   *  Silently no-ops if the tag isn't present (it's idempotent).
   *
   *  Use inside build_model body right before re-creating a selection, e.g.:
   *    BridgeUtil.clearSelection(model, "bBot");
   *    model.component("comp1").geom("geom1").selection().create("bBot", "Box");
   *
   *  Implementation note: COMSOL's Selection interface exposes `tags()` and
   *  `remove(int[])` only on concrete subtypes; the abstract Selection base
   *  type does not. We use reflection to probe safely.
   */
  static void clearSelection(com.comsol.model.Model model, String tag) {
    java.lang.reflect.Method tagsM, removeM, isInstM;
    java.util.function.BiConsumer<Object, String> tryRemove = (sel, t) -> {
      try {
        java.lang.reflect.Method tm = sel.getClass().getMethod("tags");
        Object res = tm.invoke(sel);
        if (res instanceof String[] arr && java.util.Arrays.asList(arr).contains(t)) {
          // try remove(String) first (newer API)
          try {
            sel.getClass().getMethod("remove", String.class).invoke(sel, t);
            return;
          } catch (NoSuchMethodException ns) {
            // fall back to remove(int[]) — find the tag's index in the array
            int idx = java.util.Arrays.asList(arr).indexOf(t);
            if (idx >= 0) {
              try {
                sel.getClass().getMethod("remove", int[].class).invoke(sel, new Object[]{new int[]{idx}});
              } catch (Throwable ignored) {}
            }
          }
        }
      } catch (Throwable ignored) {}
    };

    try {
      for (String c : model.component().tags()) {
        var comp = model.component(c);
        for (String g : comp.geom().tags()) {
          try { tryRemove.accept(comp.geom(g).selection(), tag); } catch (Throwable ignored) {}
        }
        for (String p : comp.physics().tags()) {
          try { tryRemove.accept(comp.physics(p).selection(), tag); } catch (Throwable ignored) {}
        }
        for (String m : comp.material().tags()) {
          try { tryRemove.accept(comp.material(m).selection(), tag); } catch (Throwable ignored) {}
        }
      }
    } catch (Throwable t) {
      System.out.println("[WARN] clearSelection(" + tag + ") probe failed: " + t.getMessage());
    }
  }

  /** Convenience: clearSelection + print marker. Use from build_model body:
   *    BridgeUtil.ensureClearSelection(model, "bBot");
   *    model.component("comp1").geom("geom1").selection().create("bBot", "Box");
   */
  static void ensureClearSelection(com.comsol.model.Model model, String tag) {
    clearSelection(model, tag);
  }
}
