# Launcher Icons

This app requires launcher icon resources. The current XML files reference icons that don't exist yet.

## Quick Fix (Android Studio)

1. Right-click on `app/src/main/res` → **New** → **Image Asset**
2. Choose **Launcher Icons (Adaptive and Legacy)**
3. Select a simple icon (or use a custom image)
4. Click **Next** → **Finish**

This will generate all required icon resources in the appropriate mipmap folders.

## Manual Creation

If building from command line, you'll need to create icon PNG files in:
- `app/src/main/res/mipmap-mdpi/ic_launcher.png` (48x48)
- `app/src/main/res/mipmap-hdpi/ic_launcher.png` (72x72)
- `app/src/main/res/mipmap-xhdpi/ic_launcher.png` (96x96)
- `app/src/main/res/mipmap-xxhdpi/ic_launcher.png` (144x144)
- `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` (192x192)

And corresponding `ic_launcher_round.png` files for round icons.

For adaptive icons (Android 8.0+), also create:
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (already exists, but needs foreground/background drawables)
- Foreground and background drawable resources

## Temporary Workaround

For testing, you can use any simple PNG image (even a solid color) as a placeholder.


