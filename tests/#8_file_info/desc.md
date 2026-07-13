#8 — inspect_model reports file_info (size/mtime/has_sol)

- **Journal:** #8 (KEPT 2026-07-10)
- **Type:** A — fixed helper (Inspect.class), comsol-batch direct, unattended
- **What it guards:** `inspect_model` must surface file hygiene (size/mtime/has_sol) so a 961MB unconverged dirty .mph doesn't sneak through unnoticed. If a refactor drops the FILE_INFO section, dirty-file blindness returns.
- **Model:** FP_ahn_conv_M0.mph (6.8MB, has_sol=1)
- **Assertion:** FILE_INFO section contains `size_mb<TAB>...` and `has_sol<TAB>...` lines.