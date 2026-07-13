import com.comsol.model.Model;
import com.comsol.model.util.ModelUtil;
import com.comsol.model.NumericalFeature;
import java.io.*;
import java.util.*;

/** Evaluate freq + S-parameters (complex) over all sweep points, write TSV.
 *  Props: model=<mph>, out=<tsv path>. Layout-robust (handles 6.4 solnum=all
 *  flattening). Output columns: freq_Hz S21_re S21_im S11_re S11_im. */
public class SparamExport {
  public static void main(String[] args) throws Exception {
    Properties in = BridgeUtil.loadInput();
    String path = in.getProperty("model");
    String out = in.getProperty("out");
    Model model = ModelUtil.load("Model", path);
    var res = model.result();
    String tag = "sp" + System.currentTimeMillis();
    NumericalFeature rf = res.numerical().create(tag, "Global");
    String[] exprs = {"freq", "ewfd.S21", "ewfd.S11"};
    rf.set("expr", exprs);
    for (String d : res.dataset().tags()) { rf.set("data", d); break; }
    try { rf.set("solnum", "all"); } catch (Throwable t) {}
    rf.run();
    double[][] real = rf.getReal();
    double[][] imag = rf.getImag();
    int nexpr = exprs.length;
    // detect layout: normal [expr][point], transposed [point][expr], or flattened [1][nexpr*npoint]
    boolean flattened = (real.length == 1) && (nexpr > 1) && (real[0].length % nexpr == 0) && (real[0].length / nexpr > 1);
    boolean transposed = !flattened && (real.length != nexpr) && (real.length > 0) && (real[0].length == nexpr);
    int np;
    boolean flatExprMajor = false;
    if (flattened) {
      np = real[0].length / nexpr;
      boolean mono = true;
      for (int p = 1; p < np; p++) if (!(real[0][p] > real[0][p-1])) { mono = false; break; }
      flatExprMajor = mono;
    } else if (transposed) np = real.length;
    else np = (real.length == 0) ? 0 : real[0].length;

    PrintWriter pw = new PrintWriter(new FileWriter(out));
    pw.println("# freq_Hz\tS21_re\tS21_im\tS11_re\tS11_im");
    for (int p = 0; p < np; p++) {
      double f, s21r, s21i, s11r, s11i;
      if (flattened) {
        if (flatExprMajor) {
          f = real[0][0*np+p]; s21r = real[0][1*np+p]; s11r = real[0][2*np+p];
          s21i = imag[0][1*np+p]; s11i = imag[0][2*np+p];
        } else {
          f = real[0][p*nexpr+0]; s21r = real[0][p*nexpr+1]; s11r = real[0][p*nexpr+2];
          s21i = imag[0][p*nexpr+1]; s11i = imag[0][p*nexpr+2];
        }
      } else if (transposed) {
        f = real[p][0]; s21r = real[p][1]; s11r = real[p][2];
        s21i = imag[p][1]; s11i = imag[p][2];
      } else {
        f = real[0][p]; s21r = real[1][p]; s11r = real[2][p];
        s21i = imag[1][p]; s11i = imag[2][p];
      }
      pw.printf("%.6e\t%.6e\t%.6e\t%.6e\t%.6e%n", f, s21r, s21i, s11r, s11i);
    }
    pw.close();
    res.numerical().remove(tag);
    BridgeUtil.section("DONE");
    System.out.println("out\t" + out);
    System.out.println("npoints\t" + np);
    System.out.println("layout\t" + (flattened?("flat "+(flatExprMajor?"exprmaj":"ptmaj")):(transposed?"transp":"normal")));
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}