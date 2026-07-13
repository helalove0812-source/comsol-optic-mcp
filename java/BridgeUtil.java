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

  // ---------------------------------------------------------------------
  // MCP optimization (2026-07-11): mesh-selection helpers + audit.
  // The recurring silent bug: a mesh feature's selection MUST be resolved to
  // a geometry+dim before .set(int[]), i.e.
  //   feat.selection().geom("geom1", 2).set(new int[]{5})
  // Calling feat.selection().set(int[]) WITHOUT .geom(geom,dim) silently
  // yields an EMPTY selection — the feature then applies to nothing, with NO
  // error at solve time, and the mesh just isn't refined. meshSelect() bakes
  // in the correct call; auditMesh() reports per-feature entity counts so an
  // empty selection becomes visible data instead of a silent skip.
  // ---------------------------------------------------------------------

  /** Apply an entity selection to a mesh feature, the CORRECT way (resolve
   *  geometry+dim before .set(int[])). Use this instead of hand-writing
   *  `feat.selection().set(...)` to avoid the silent-empty-selection bug.
   *
   *  Example (Size feature on domain 5 of a 2D geom):
   *    BridgeUtil.meshSelect(model, "comp1", "mesh1", "szLiq", "geom1", 2, new int[]{5});
   *  is equivalent to (and safer than):
   *    model.component("comp1").mesh("mesh1").feature("szLiq")
   *         .selection().geom("geom1", 2).set(new int[]{5});
   */
  static void meshSelect(com.comsol.model.Model model, String compTag,
      String meshTag, String featTag, String geomTag, int dim, int[] entities) {
    model.component(compTag).mesh(meshTag).feature(featTag)
         .selection().geom(geomTag, dim).set(entities);
  }

  /** Emit a <<<MESH_AUDIT>>> section listing every mesh feature with the
   *  number of selected entities (n). A feature the user intended to scope
   *  to specific domains but which shows n=0 is the signature of a silent
   *  empty selection (e.g. .geom(geom,dim).set(int[]) where the entity ids
   *  don't exist in the current geometry, or the wrong dim). n=-1 means the
   *  probe threw (the feature has no entity selection, e.g. some Map/global
   *  Size features) — not necessarily a bug.
   *
   *  Read path: feat.selection().entities() returns the feature's BOUND
   *  selection. Do NOT call .geom(geomTag,dim) first — that REBINDS to a
   *  fresh empty selection (it is a setter, not a getter) and would always
   *  report 0. Never throws; a failed audit prints one [WARN] line so the
   *  build still completes and saves. */
  static void auditMesh(com.comsol.model.Model model) {
    try {
      section("MESH_AUDIT");
      System.out.println("comp\tmesh\tfeat\ttype\tn");
      for (String c : model.component().tags()) {
        var comp = model.component(c);
        String[] meshTags;
        try { meshTags = comp.mesh().tags(); } catch (Throwable t) { meshTags = new String[0]; }
        for (String mt : meshTags) {
          String[] feats;
          try { feats = comp.mesh(mt).feature().tags(); } catch (Throwable t) { feats = new String[0]; }
          for (String ft : feats) {
            String ty = "?";
            int n = -1;
            try {
              var feat = comp.mesh(mt).feature(ft);   // MeshFeature
              try { ty = feat.getType(); } catch (Throwable t) { ty = "?"; }
              // read the feature's bound selection — entities() with no .geom()
              try { n = feat.selection().entities().length; } catch (Throwable ignored) {}
            } catch (Throwable t) {
              ty = "err:" + t.getClass().getSimpleName();
            }
            System.out.println(c + "\t" + mt + "\t" + ft + "\t" + ty + "\t" + n);
          }
        }
      }
    } catch (Throwable t) {
      System.out.println("[WARN] auditMesh failed: " + t.getMessage());
    }
  }
}
