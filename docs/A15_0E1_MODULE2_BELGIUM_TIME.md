# A15.0E1 — Module2 Belgian seasonal time fix

Module2 validates `posDateTime` against Belgian civil time. A timestamp sent with `+01:00` during summer time (or `+02:00` during winter time) is rejected.

This patch:
- uses `Europe/Brussels` for all manually generated Module2 TRAINING timestamps;
- preserves the immutable fiscal snapshot instant;
- converts that same instant to the correct Belgian seasonal offset before `signSale`;
- derives `bookingDate` from the normalized Belgian timestamp;
- does not change the FDM clock and does not rewrite the snapshot/hash.

Expected on 22 September 2026: `+02:00`.
Expected during Belgian winter time: `+01:00`.
