import com.comsol.model.Model;
import com.comsol.model.util.ModelUtil;
import java.io.File;
import java.util.*;

/** Inspect a model: parameters, components/physics, studies/features, datasets, result nodes.
 *  MCP opt #8: also emits a FILE_INFO section with mtime/size/has_sol/study_status so
 *  the user can detect "dirty" .mph files (e.g. v1 runs that never converged, leaving
 *  huge 1GB files with no real solution). */
public class Inspect {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String input = in.getProperty("model");
    if (input == null) { BridgeUtil.fail("missing 'model'"); return null; }
    Model model = ModelUtil.load("Model", input);

    // MCP opt #8: FILE_INFO before anything else
    try {
      BridgeUtil.section("FILE_INFO");
      File f = new File(input);
      long sizeBytes = f.exists() ? f.length() : -1;
      double sizeMb = (sizeBytes >= 0) ? (sizeBytes / 1.0e6) : -1.0;
      long mtime = f.exists() ? f.lastModified() : 0;
      String mtimeIso = (mtime > 0) ? new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new java.util.Date(mtime)) : "?";
      System.out.println("path\t" + f.getAbsolutePath());
      System.out.println("size_mb\t" + (Math.round(sizeMb * 100.0) / 100.0));
      System.out.println("mtime\t" + mtimeIso);
      // has_sol: probe whether any study has a solved solution (best-effort)
      boolean hasSol = false;
      try {
        String[] dtags = model.result().dataset().tags();
        for (String dt : dtags) {
          String label = model.result().dataset(dt).label();
          if (label != null) { hasSol = true; break; }
        }
      } catch (Throwable t) {}
      System.out.println("has_sol\t" + (hasSol ? "1" : "0"));
      // per-study status
      for (String s : model.study().tags()) {
        String status = "unknown";
        try {
          var st = model.study(s);
          // probe whether the study has been run; COMSOL doesn't expose a direct API
          // for this, so we rely on the existence of a corresponding solution dataset
          String solTag = "sol" + s.replaceAll("[^0-9]", "");
          if (model.result().dataset().tags() != null) {
            String[] dtags = model.result().dataset().tags();
            for (String dt : dtags) {
              if (dt.equalsIgnoreCase(solTag)) { status = "solved"; break; }
            }
          }
          if (status.equals("unknown")) status = "unsolved";
        } catch (Throwable t) {
          status = "error";
        }
        System.out.println("study_status\t" + s + "\t" + status);
      }
    } catch (Throwable t) {
      System.out.println("file_info_err\t" + t.getMessage());
    }

    BridgeUtil.section("PARAMS");
    for (String tag : model.param().varnames()) {
      System.out.println(tag + "\t" + model.param().get(tag));
    }

    BridgeUtil.section("COMPONENTS");
    for (String c : model.component().tags()) {
      System.out.println("comp\t" + c);
      for (String p : model.component(c).physics().tags()) {
        System.out.println("physics\t" + c + "\t" + p + "\t" + model.component(c).physics(p).getType());
      }
      for (String m : model.component(c).material().tags()) {
        System.out.println("material\t" + c + "\t" + m + "\t" + model.component(c).material(m).label());
      }
    }

    BridgeUtil.section("STUDIES");
    for (String s : model.study().tags()) {
      System.out.println("study\t" + s + "\t" + model.study(s).label());
      for (String f : model.study(s).feature().tags()) {
        System.out.println("feat\t" + s + "\t" + f + "\t" + model.study(s).feature(f).getType());
      }
    }

    try {
      BridgeUtil.section("DATASETS");
      for (String d : model.result().dataset().tags()) {
        System.out.println("dset\t" + d + "\t" + model.result().dataset(d).label());
      }
    } catch (Throwable t) { /* result not available on unsolved models */ }

    try {
      BridgeUtil.section("NUMERICAL");
      for (String n : model.result().numerical().tags()) {
        System.out.println("num\t" + n + "\t" + model.result().numerical(n).label());
      }
    } catch (Throwable t) { /* ignore */ }

    return model;
  }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}