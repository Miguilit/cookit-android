# Cookit Android — iOS-inspired visual polish P1

Date: 2026-09-29
Baseline: Android 0.15.0.47 after Web/Android/iPad non-fiscal POS parity.

## Goal
Bring the visual language that works well on CookitPad to Android without copying iOS controls and without changing Android information architecture, navigation, workflows, API contracts, fiscal runtime or POS behavior.

## P1 visual foundation
- near-black application canvas;
- anthracite cards/surfaces;
- cyan interaction accent for selected navigation, selected order/payment choices and primary Material actions;
- Cookit orange preserved as brand/attention color;
- green preserved for positive/success/money semantics;
- subtle dark borders instead of large shadows;
- slightly tighter top bar and restrained corner radii;
- POS product cards, modifiers badge, cart, mobile cart bar, billing sheet and order cards use the same dark surface family;
- dark Android status/navigation bars.

## Frozen in P1
No route, screen destination, API call, business rule, order flow, payment flow, cash-register rule, device identity, printer, offline storage, fiscal agent/FDM/Module2 behavior or Android permission is changed.

The tablet keeps its existing Android side navigation + top bar and its existing POS layout. P1 is a visual layer only.

## Next visual tranche
After a screenshot/device review, P2 can refine density, typography hierarchy, compact controls and screen-by-screen consistency without changing structure.
