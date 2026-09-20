# A14.5.1 — C3.3 lost-response-once recovery

Purpose: validate the Android Fiscal Agent's lease retry + FDM idempotency path without a real certified FDM.

Changes:
- Cloud test metadata can select a Mock scenario only when the current FDM provider is the debug Mock and metadata contains `test_only=true`.
- Scenario values are allowlisted; they are never forwarded to the production Checkbox/Eutronix adapter.
- New debug Mock scenario `lost_response_once`:
  - first call durably stores the receipt and drops the HTTP response;
  - Cloud job remains claimed and no submitted/ack is sent;
  - after the Cloud lease expires, the same job can be claimed again;
  - second call uses the same idempotency key, returns the same receipt with `duplicate=true`;
  - Android then sends submitted + acknowledge once.
- Release Mock remains disabled. Checkbox/Eutronix remains fail-closed.

This is resilience test infrastructure only. It is not Belgian fiscal certification and does not activate the fiscal profile.
