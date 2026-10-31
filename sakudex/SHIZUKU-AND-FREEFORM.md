# Shizuku and Freeform foundation

## Shizuku

Saku DeX includes the official Shizuku API and provider. The taskbar S control checks whether the Shizuku binder is available and requests Saku DeX's own Shizuku permission when needed. Permission is granted or denied by the user in the Shizuku manager.

Important:
- Shizuku is not bundled; install and start Shizuku or Sui separately.
- ADB-backed Shizuku and root-backed Shizuku have different capabilities. The app reports the server UID and must check capability before each future privileged operation.
- A granted Shizuku permission does not mean every privileged API is available.
- No privileged system changes are executed by this stage.

## Freeform window roadmap

App launching currently uses the standard Android launcher intent: ACTION_MAIN plus CATEGORY_LAUNCHER. This is intentionally the reliable fallback.

The next window-management stage can:
1. Detect desktop/freeform support and Android version.
2. Offer an explicit Open in window action only on supported devices.
3. Use public launch bounds as a best-effort hint where supported.
4. Investigate device/vendor desktop-mode APIs for actual freeform task creation.

Android freeform behavior is device- and system-policy-dependent. A normal app cannot guarantee that another app opens in a floating window simply by adding an intent flag. Shizuku may enable selected system APIs on compatible builds, but each API must be capability-checked and tested separately.
