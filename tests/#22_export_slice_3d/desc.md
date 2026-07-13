# #22 — export_slice_3d (3D Slice plot, batch -3drend sw)

**journal #22 / principles C2-C4.** Regression for the 3D native-image path.

## What it guards
`ExportCutPlane.class` (PlotGroup3D + Slice plot feature) renders a colored 3D
cut plane through `dset1` in headless batch. The breakthrough (vs the blocked
paths): batch's `model.result()` context forbids geometry ops — no cut-plane
**dataset**, no plot-feature **selection** (Hide/Surface `.geom().set()` all
fail). The Slice **plot feature** defines the plane internally; rendering is an
eval op (allowed). Key props: `quickplane` + `quickz` (level) +
`quickznumber=1` (count — NOT `quickplanesnumber`, which is "Unknown property").
3D decorations: `title3d`/`legend3d`/`axes3d` on the Image export node.

## Why it can SKIP
Needs a **solved 3D** .mph with `dset1`. No small 3D test model lives in-repo
(the Proscia `mim_v38.mph` is 385 MB). Without `COMSOL_3D_MODEL` set the test
SKIPs (exit 0 with `#22 SKIP`), so it doesn't fail the gate.

## To run it (manual / CI with a 3D model)
```
COMSOL_3D_MODEL=/home/pengyk/Project/Proscia2023_MIM_Lcavity/01_models/mim_v38.mph \
  ./tests/#22_export_slice_3d/run.sh
```
Asserts `pngSize > 50000` — a colored slice is ~120 KB; a wireframe-only render
(the regression signature: `quickznumber` not set → 0 slices) is ~22 KB.

## Real-world proof (07-13, visionpro-verified)
On `mim_v38.mph` produced 4 figures: `Struct_Lpatch_slice` (dom, L-shape),
`Field_LP_slice` (|E| @1670 cm⁻¹, L-shaped hot-spot under the patch),
`Field_offres_slice` (1500, uniform control), `Field_UP_slice` (1850). All with
colorbar/title/axes. See [[proscia-mim-2026-07-11]].