# Cookit Android A15.0C — Module2 TRAINING signSale

## Scope

This phase validates the first fiscal GraphQL mutation against the Module2 simulator without enabling Cookit automatic fiscal processing.

- Provider remains `module2_pracsys`.
- mTLS + Bearer + hostname compatibility from A15.0B3 are retained.
- The new action is manual only.
- `signSale` is sent with `isTraining=true`.
- The payload mirrors the sample transaction published in the Module2 developer manual: two products (24 + 28 EUR) and one 52 EUR cash payment.
- The simulator-allowed POS id `CPOS0031234567` is used.
- Training ticket numbers, device id and booking-period id are persisted locally for repeatable tests.
- The automatic Cookit fiscal agent remains stopped/gated. Real Cookit order mapping is intentionally not enabled in this phase.

## Expected successful response

Cookit displays the returned FDM id, training event label, event counter, total counter, short signature, VAT calculation and latency. A training event should be returned with event label `T`.

## If the simulator is not initialized

The current simulator can report `initialized=false`. A15.0C intentionally does not bypass or fake this state. If Module2 rejects the TRAINING mutation because initialization is required, Cookit surfaces the exact GraphQL error without crashing. That response becomes the input for the next integration step.

## Safety

This phase must not be used for production sales. Automatic fiscal processing remains disabled until the real Cookit `SaleInput` mapping, numbering, employee identity, VAT mapping, payments, retry/idempotency handling and production provisioning are completed and validated.
