import com.comsol.model.*;
import com.comsol.model.util.ModelUtil;
import java.util.*;

/** DispMaterial: add a dispersive epsilon(omega) material to a model by writing
 *  analytic Lorentz / Drude expressions into a RefractiveIndex property group
 *  (n = real(sqrt(eps)), ki = imag(sqrt(eps))).
 *
 *  WHY ANALYTIC (not the built-in "Dispersion"/"Lorentz" propertyGroup):
 *  COMSOL 6.4 ships Dispersion/Lorentz/DrudeLorentz propertyGroup types, but
 *  MaterialModel.set(name, val) is PERMISSIVE — it accepts and stores any
 *  property name (verified: bogus "ZZBOGUS_PROP_zzz" is stored and listed by
 *  properties()), so the real Lorentz oscillator schema (omegaLOrntz_k etc.)
 *  cannot be discovered via set-and-probe, and the jar byte-code does not
 *  expose the names as plain literals. Instead we feed the closed-form complex
 *  epsilon(omega) to the RefractiveIndex group, whose `n` and `ki` setters are
 *  proven (FP_ahn Cr/SiO2 use them). In a frequency-domain sweep each freq is
 *  solved independently, so a steady-state analytic eps(omega) is physically
 *  exact — no auxiliary-DOF memory terms are needed.
 *
 *  Convention (COMSOL uses e^{i w t}; passive/lossy => Im(eps) <= 0):
 *    eps(omega) = epsinf
 *               + sum_k  S_k * wTO_k^2 / (wTO_k^2 - w^2 + i*w*gamma_k)   [Lorentz]
 *               - wp^2 / (w^2 - i*w*gammaD)                              [Drude]
 *    with w = 2*pi*freq (freq = the ewfd sweep variable, Hz).
 *  NOTE: the i-term signs are the CONJUGATE of the usual e^{-iwt} physics
 *  convention, because COMSOL uses e^{+iwt}. Under e^{+iwt}, passive loss
 *  requires Im(eps) <= 0 (time-avg absorbed power ~ -w*Im(eps)|E|^2/2 > 0).
 *  With the +i*w*gamma Lorentz / -i*w*gammaD Drude signs here, Im(eps) <= 0
 *  for all S>0,wp>0,gamma>0 => passive absorption; ki = imag(sqrt(eps)) > 0.
 *  A SANITY section is emitted sampling Re/Im(eps) at the resonances and
 *  asserting Im(eps) <= 0 (passive_check) — a gain material (Im>0 => R>1)
 *  fails loud, never silently.
 *
 *  Oscillator frequencies (fTO_k, gamma_k, wp, gammaD) are CYCLIC frequencies
 *  in Hz (write them with units, e.g. "1[THz]"); the helper converts to angular
 *  via 2*pi internally. epsinf and S_k are dimensionless.
 *
 *  Input:
 *    model    = path to .mph to modify
 *    comp     = component tag (default comp1)
 *    tag      = material tag to create (e.g. matAu)
 *    label    = material label (optional)
 *    domains  = csv domain ids for the material selection (e.g. 2,8)
 *    kind     = "lorentz" | "drude" | "lorentz+drude"  (default lorentz)
 *    epsinf   = high-freq permittivity expr (e.g. 5.5 ; 1 for pure metal)
 *    lorentz  = (kind lorentz/lorentz+drude) semicolon-separated oscillators,
 *               each "fTO,S,gamma" (cyclic freq Hz, dimensionless, damping Hz),
 *               e.g. "1[THz],12,0.05[THz];1.9[THz],8,0.1[THz]"
 *    drude    = (kind drude/lorentz+drude) "wp,gammaD" (cyclic freq Hz, damping Hz),
 *               e.g. "2000[THz],20[THz]"
 *    output   = path to save the modified .mph (optional; if omitted, NOT saved)
 *
 *  Output sections:
 *    DISP     summary: kind, epsinf, n_lorentz, n_drude, the eps expr, n expr, ki expr,
 *             the RefractiveIndex identifier, domain selection
 *    PARAMS   the oscillator params created on model.param() (name \t value)
 *    SAVED    output path (if output given)
 *
 *  Reuses the MaterialInfo identifier lesson: a freshly created RefractiveIndex
 *  group has identifier = its tag (here "rg1"), which ewfd reads correctly
 *  (FP_ahn's Cr/SiO2 groups use "rg1"). So we do NOT need duplicate().
 */
public class DispMaterial {
  public static Model run() throws Exception {
    Properties in = BridgeUtil.loadInput();
    String path = in.getProperty("model");
    if (path == null || path.isEmpty()) { BridgeUtil.fail("missing 'model'"); return null; }
    String comp = in.getProperty("comp", "comp1").trim();
    String mtag = in.getProperty("tag", "").trim();
    String label = in.getProperty("label", "").trim();
    String domCsv = in.getProperty("domains", "").trim();
    String kind = in.getProperty("kind", "lorentz").trim().toLowerCase();
    String epsinf = in.getProperty("epsinf", "1").trim();
    String lorentz = in.getProperty("lorentz", "").trim();
    String drude = in.getProperty("drude", "").trim();
    String output = in.getProperty("output", "").trim();
    if (mtag.isEmpty()) { BridgeUtil.fail("missing 'tag'"); return null; }

    Model model = ModelUtil.load("Model", path);

    boolean useLorentz = kind.contains("lorentz");
    boolean useDrude   = kind.contains("drude");
    if (!useLorentz && !useDrude) { BridgeUtil.fail("unknown kind: " + kind + " (lorentz|drude|lorentz+drude)"); return model; }

    // ---- 1. register oscillator params on model.param() so they're sweepable ----
    String P = mtag + "_";            // param name prefix
    List<String> lorentzTerms = new ArrayList<>();
    List<double[]> lorAng = new ArrayList<>();   // {wTO, S, gamma_w} for SANITY
    int nLor = 0;
    if (useLorentz) {
      String[] oscs = lorentz.isEmpty() ? new String[0] : lorentz.split(";");
      for (String osc : oscs) {
        String[] f = osc.split(",");
        if (f.length != 3) { BridgeUtil.fail("lorentz osc must be fTO,S,gamma; got: " + osc); return model; }
        String fto = f[0].trim(), s = f[1].trim(), g = f[2].trim();
        nLor++;
        String idx = String.valueOf(nLor);
        setParam(model, P + "fTO" + idx, fto, "Lorentz osc " + idx + " resonance (Hz)");
        setParam(model, P + "S"   + idx, s,   "Lorentz osc " + idx + " strength");
        setParam(model, P + "g"   + idx, g,   "Lorentz osc " + idx + " damping (Hz)");
        // w = 2*pi*freq ; wTO = 2*pi*fTO ; gamma = 2*pi*g
        String term = "(" + P + "S" + idx + ")*(2*pi*" + P + "fTO" + idx + ")^2 / "
          + "((2*pi*" + P + "fTO" + idx + ")^2 - (2*pi*freq)^2 + i*(2*pi*freq)*(2*pi*" + P + "g" + idx + "))";
        lorentzTerms.add(term);
        // store angular values for SANITY (parse cyclic-Hz exprs)
        try {
          double fTOHz = parseFreqHz(fto);
          double sVal = Double.parseDouble(s);
          double gHz = parseFreqHz(g);
          lorAng.add(new double[]{2*Math.PI*fTOHz, sVal, 2*Math.PI*gHz});
        } catch (Throwable pe) { BridgeUtil.fail("SANITY parse failed for osc " + idx + ": " + pe.getMessage()); }
      }
    }
    List<String> drudeTerms = new ArrayList<>();
    double[] drudeAng = null;   // {wp_w, gammaD_w}
    if (useDrude) {
      String[] f = drude.isEmpty() ? new String[0] : drude.split(",");
      if (f.length != 2) { BridgeUtil.fail("drude must be wp,gammaD; got: " + drude); return model; }
      String wp = f[0].trim(), gd = f[1].trim();
      setParam(model, P + "wp", wp, "Drude plasma freq (Hz)");
      setParam(model, P + "gammaD", gd, "Drude damping (Hz)");
      // eps_Drude = -wp^2 / (w^2 - i*w*gammaD),  w = 2*pi*freq  (e^{+iwt} passive)
      String term = "-(" + P + "wp)^2 / ((2*pi*freq)^2 - i*(2*pi*freq)*(2*pi*" + P + "gammaD))";
      drudeTerms.add(term);
      try {
        double wpHz = parseFreqHz(wp), gdHz = parseFreqHz(gd);
        drudeAng = new double[]{2*Math.PI*wpHz, 2*Math.PI*gdHz};
      } catch (Throwable pe) { BridgeUtil.fail("SANITY parse failed for Drude: " + pe.getMessage()); }
    }

    // ---- 2. assemble eps expression ----
    StringBuilder eps = new StringBuilder("(").append(epsinf);
    for (String t : lorentzTerms) eps.append(" + ").append(t);
    for (String t : drudeTerms)   eps.append(" + ").append(t);
    eps.append(")");
    String epsExpr = eps.toString();
    String nExpr  = "real(sqrt(" + epsExpr + "))";
    String kiExpr = "imag(sqrt(" + epsExpr + "))";

    // ---- 3. create material + RefractiveIndex group ----
    try { model.component(comp).material().remove(mtag); } catch (Throwable ignored) {}
    Material mat = model.component(comp).material().create(mtag, "Common");
    if (!label.isEmpty()) { try { mat.label(label); } catch (Throwable ignored) {} }
    // remove any pre-existing property group on this material to start clean
    for (String pg : mat.propertyGroup().tags()) {
      try { mat.propertyGroup().remove(pg); } catch (Throwable ignored) {}
    }
    mat.propertyGroup().create("rg1", "RefractiveIndex");
    var pg = mat.propertyGroup("rg1");
    pg.set("n", nExpr);
    pg.set("ki", kiExpr);
    String identifier = "?";
    try { identifier = pg.identifier(); } catch (Throwable ignored) {}

    // selection
    if (!domCsv.isEmpty()) {
      String[] ds = domCsv.split(",");
      int[] doms = new int[ds.length];
      for (int i = 0; i < ds.length; i++) doms[i] = Integer.parseInt(ds[i].trim());
      mat.selection().set(doms);
    }

    // ---- 4. emit summary ----
    BridgeUtil.section("DISP");
    System.out.println("tag\t" + mtag + "\tlabel\t" + (label.isEmpty() ? "(none)" : label));
    System.out.println("kind\t" + kind + "\tepsinf\t" + epsinf);
    System.out.println("n_lorentz\t" + nLor + "\tn_drude\t" + (useDrude ? 1 : 0));
    System.out.println("identifier\t" + identifier);
    System.out.println("domains\t" + domCsv);
    System.out.println("eps_expr\t" + epsExpr);
    System.out.println("n_expr\t" + nExpr);
    System.out.println("ki_expr\t" + kiExpr);

    // ---- 4b. SANITY: sample eps(omega) at the resonances, assert passive ----
    // COMSOL uses e^{+i w t} => passive loss needs Im(eps) <= 0. A flipped sign
    // (gain) gives Im(eps) > 0 => R > 1 (unphysical). This self-check fails loud
    // so a wrong-sign material never gets written silently (journal #11).
    BridgeUtil.section("SANITY");
    double epsinfNum;
    try { epsinfNum = Double.parseDouble(epsinf); }
    catch (Throwable e) { epsinfNum = Double.NaN; }
    boolean passive = true;
    double worstIm = Double.NEGATIVE_INFINITY;
    System.out.println("convention\te^{+i w t}  (passive => Im(eps) <= 0)");
    if (Double.isNaN(epsinfNum)) {
      System.out.println("passive_check\tSKIP (epsinf not a literal: " + epsinf + ")");
    } else {
      // sample angular frequencies: around each Lorentz resonance + Drude band
      List<Double> ws = new ArrayList<>();
      for (double[] o : lorAng) { ws.add(0.8*o[0]); ws.add(o[0]); ws.add(1.2*o[0]); }
      if (drudeAng != null) { ws.add(0.2*drudeAng[0]); ws.add(0.5*drudeAng[0]); ws.add(drudeAng[0]); }
      if (ws.isEmpty()) ws.add(1.0);  // something
      for (double w : ws) {
        double[] eri = epsReIm(w, epsinfNum, lorAng, drudeAng);
        worstIm = Math.max(worstIm, eri[1]);
        if (eri[1] > 1e-6) passive = false;
        // f = w/(2pi); print cyclic THz for readability
        System.out.printf("sample\t%.6e\tRe_eps\t%.6e\tIm_eps\t%.6e%n", w, eri[0], eri[1]);
      }
      System.out.printf("worst_Im_eps\t%.6e%n", worstIm);
      System.out.println("passive_check\t" + (passive ? "PASS" : "FAIL (Im(eps)>0 somewhere => gain => R>1; sign convention wrong)"));
    }

    BridgeUtil.section("PARAMS");
    for (String pn : model.param().varnames()) {
      if (!pn.startsWith(P)) continue;
      String v = "?";
      try { v = model.param().get(pn); } catch (Throwable ignored) {}
      System.out.println(pn + "\t" + v);
    }

    if (!output.isEmpty()) {
      model.save(output);
      BridgeUtil.section("SAVED");
      System.out.println("output\t" + output);
    }
    return model;
  }

  private static void setParam(Model m, String name, String expr, String desc) {
    try { m.param().set(name, expr, desc); }
    catch (Throwable t) { BridgeUtil.fail("set param " + name + " failed: " + t.getMessage()); }
  }

  /** Parse a COMSOL cyclic-frequency expression like "1[THz]" / "0.05[THz]" / "2000[GHz]"
   *  to Hz. Bare number (no unit) assumed Hz. Used only for the SANITY self-check. */
  private static double parseFreqHz(String s) {
    s = s.trim();
    double mult = 1.0;
    int bi = s.indexOf('[');
    if (bi >= 0) {
      int be = s.indexOf(']', bi);
      String unit = s.substring(bi + 1, be).trim().toLowerCase();
      if (unit.endsWith("hz")) unit = unit.substring(0, unit.length() - 2);
      switch (unit) {
        case "t": mult = 1e12; break;
        case "g": mult = 1e9; break;
        case "m": mult = 1e6; break;
        case "k": mult = 1e3; break;
        default:  mult = 1.0; break;   // unknown / "Hz" / bare => Hz
      }
      s = s.substring(0, bi);
    }
    return Double.parseDouble(s.trim()) * mult;
  }

  /** Closed-form eps(w) with the e^{+iwt} passive signs (must match the expr
   *  strings written to COMSOL). lor entries: {wTO, S, gamma_w}. drude: {wp_w, gammaD_w} or null.
   *  Returns {Re(eps), Im(eps)}. */
  private static double[] epsReIm(double w, double epsinf,
                                  List<double[]> lor, double[] drude) {
    double re = epsinf, im = 0.0;
    for (double[] o : lor) {
      double wTO = o[0], S = o[1], gam = o[2];
      double dRe = wTO * wTO - w * w;
      double dIm = w * gam;                  // + i*w*gamma (FIXED, passive)
      double d2 = dRe * dRe + dIm * dIm;
      re += S * wTO * wTO * dRe / d2;
      im += -S * wTO * wTO * dIm / d2;       // Im(term) = -S wTO^2 w gamma / |D|^2 <= 0
    }
    if (drude != null) {
      double wp = drude[0], gamD = drude[1];
      double dRe = w * w;
      double dIm = -w * gamD;                 // w^2 - i*w*gammaD (FIXED, passive)
      double d2 = dRe * dRe + dIm * dIm;
      re += -wp * wp * dRe / d2;
      im += wp * wp * dIm / d2;               // = -wp^2 w gammaD / |D|^2 <= 0
    }
    return new double[]{re, im};
  }

  public static void main(String[] args) throws Exception {
    run();
    ModelUtil.remove("Model");
    BridgeUtil.end();
  }
}