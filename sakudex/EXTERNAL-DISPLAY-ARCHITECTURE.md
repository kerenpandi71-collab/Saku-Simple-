# Saku DeX external display architecture

## Physical monitor / TV

- Android's DisplayManager reports connected displays.
- Displays suitable for secondary content are exposed through DISPLAY_CATEGORY_PRESENTATION.
- ExternalDisplayController listens for add/change/remove events.
- DesktopPresentation is a separate window attached to the external Display. It is not a phone-screen mirror.
- The current presentation is a small proof-of-concept shell. The next stage should host a dedicated Compose desktop root inside a ComposeView and provide the appropriate lifecycle, saved-state and view-model owners.

## Presentation vs VirtualDisplay

Use Presentation for a real HDMI / USB-C DisplayPort / wireless display that Android exposes as a secondary display. Whether a phone exposes wired video output depends on its hardware, USB-C implementation, kernel and vendor firmware; an app cannot add missing physical video-out support.

Use VirtualDisplay when software needs a separately rendered display surface, such as capture, remote display, testing, or a controlled projection pipeline. Creating a VirtualDisplay does not itself send pixels to an HDMI monitor and does not enable hardware video output.

## Proposed components

1. DisplayRepository: enumerate displays and publish connection/capability state.
2. ExternalDesktopPresentation: own the external display window and lifecycle.
3. DesktopContent: reusable Compose desktop UI, rendered independently on phone and external display.
4. Window/task coordinator: later manage app tasks and freeform/windowing where the OS and device support it.

The phone display should remain the control surface while the external display hosts the desktop. Avoid assuming that every secondary display supports interactive touch input.
