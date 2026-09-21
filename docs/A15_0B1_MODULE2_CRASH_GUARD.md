# A15.0B1 Module2 crash guard

Version: 0.15.0.2 (versionCode 37)

Purpose:
- stop the already-running Fiscal Agent when switching provider or selecting a provider whose fiscal mutation mapping is not enabled;
- self-stop an already persisted auto Fiscal Agent on app/service restart when the selected provider is intentionally gated;
- prevent any uncaught Module2 status exception from terminating the POS UI process;
- catch TLS/PKCS12 setup failures inside the Module2 status result path;
- install a service-level coroutine exception guard so a background fiscal coroutine cannot crash the POS process;
- keep Module2 signSale fail-closed. This hotfix remains read-only against Module2.

Important: A15.0B1 does not enable Module2 signSale.
