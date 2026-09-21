# A15.0D1 — RestApi tax-array parsing

Version: Cookit Android 0.15.0.7 (versionCode 42)

## Purpose
Cookit RestApi already eager-loads `MenuItem::taxes` and serializes it as a JSON array.
A15.0D parsed flat tax fields and nested `tax` objects but did not inspect the `taxes` array,
so valid item VAT configuration was shown as `VAT mapping Missing`.

## Change
`CookitHttpClient.extractVatRate()` now reads `taxes[].tax_percent` (plus compatible rate names).
It only returns a rate when the array resolves to one distinct effective rate; multiple different
rates remain fail-closed. Optional GKS labels are read only when explicitly A/B/C/D/X.

## Expected test
For menu items 117 and 119 configured with `TVA 12%`, reload the catalogue, add both items,
open Fiscality and confirm `VAT mapping Ready`. Then run the Cookit cart TRAINING sale.
