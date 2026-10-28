# TCL Google TV personal theme

An Apple TV style bottom dock with smaller tiles, optional Unsplash photos, left/right carousels and the native TV settings overlay. See [the fork guide](../../docs/FORK.md) for setup and screenshots.

## Personal layout controls

This theme uses tiles at 70% of their original size. Hold OK on Settings and open
Launcher settings to change Tile size (50–100%) or Tile row position (0% near the
top, 100% near the bottom). The clock and header keep their space. Use Reorder
apps to move individual apps between slots. Follow theme resets the size and position.

Unsplash keeps its existing private credentials across APK updates. Offline local
wallpaper rotation, captions, and the upstream wallpaper picker remain available.

## Native remote Settings overlay

The Google TV dashboard is hosted by `com.google.android.apps.tv.launcherx`.
Keep that package enabled and keep `com.gathin.tvhome/.MainActivity` selected as
default Home. TCL firmware may still route Home to Google TV, so `stock_home_redirect` sends only its home activity back to CleanYourTV; the native dashboard stays open. This theme leaves `settings_key_keycodes` empty so the gear key
reaches the native dashboard. Mapping key 83 to Android Settings replaces the
overlay with the full settings page. The separate launcher-settings button opens
tile controls without affecting the remote gear button.

## Compact and side docks

Dock placement selects Bottom edge, Left carousel or Right carousel. Bottom edge
is compact and aligned near the screen edge. Side docks enlarge the focused app
and dim its smaller neighbors; Up/Down cycles through the dock and Left/Right
toward the screen center reaches the settings button.

Use Add or remove dock apps in Launcher settings, or the dock’s add tile. Hold OK
on any dock app to remove its shortcut, move it earlier/later, or add apps. Changes
are saved immediately. These operations never uninstall apps. Theme layout keeps
the upstream grid and its separate Tile row position control.
