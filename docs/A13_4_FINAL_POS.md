# Cookit Android A13.4 — POS core final consolidation

This wave closes the agreed POS workflow before notification work.

## A12.2 UI polish

- dark Android status bar so time/date/network/battery stay readable;
- no permanent post-action comment banners in the POS;
- menu cards prioritize a large product image, name and price;
- product descriptions are intentionally omitted from POS cards.

## A13 split bill

The Android client consumes a dedicated RestApi facade which delegates mutations to Cookit's existing split-bill domain.

Supported UI workflows:

- equal split;
- custom-amount split;
- item/quantity split;
- list existing split bills;
- pay each split with cash or card/terminal;
- cancel the split and restore the order billing workflow.

## A13 table merge

The Android client uses Cookit's existing dining-session/table-service mechanics through the native facade.

Supported UI workflows:

- discover other tables with unpaid orders;
- merge one or more tables into the current dining session;
- show the merged table group and each outstanding order;
- one cashier action can settle every outstanding order in the merged group;
- unmerge secondary tables.

The native app does not introduce a second split/merge domain.

## Stabilization rules

- financial status and kitchen/order progress remain separate;
- a reopened order remains the canonical server order;
- normal checkout is redirected to Split/Merge while an active split or merged group exists;
- merged-group payment uses the existing Cookit order payment API for each outstanding canonical order;
- cash group payment supports tendered amount and change computation in the POS UI.

## Version

- versionName: `0.13.4`
- versionCode: `7`
