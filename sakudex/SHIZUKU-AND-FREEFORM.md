# Shizuku foundation

## Shizuku
Saku DeX includes the official Shizuku API and provider. The taskbar S control checks whether the Shizuku binder is available and requests Saku DeX's own Shizuku permission when needed.

Important:
- Shizuku is not bundled; install and start Shizuku or Sui separately.
- ADB-backed Shizuku and root-backed Shizuku have different capabilities. The app reports the server UID and must check capability before each future privileged operation.
- A granted Shizuku permission does not mean every privileged API is available.
- No privileged system changes are executed by this stage.

## Window management
The previous experimental Freeform Windows implementation has been removed from Saku DeX. This removes the launch-bounds placeholder, its mode toggle, long-press window launch, and the old taskbar shortcut. App launching now uses the normal Android launcher path only.

A replacement window manager must be ported and integrated as a complete feature from Saku-Taskbar, including its workspace/activity lifecycle and Android-version-specific windowing APIs. It must not reuse the removed launch-bounds-only implementation.
