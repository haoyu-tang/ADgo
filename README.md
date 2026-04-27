# ADgo — Automatic Ad Skip & Popup Dismisser

ADgo is an Android accessibility-service app that automatically detects and taps **Skip / Next / Close** buttons on in-app ads. It combines text + view-ID matching, ad-context keyword analysis, and layout-based popup validation to keep false positives low.

---

## How It Works

```
Accessibility Event
  ├─ TYPE_WINDOW_STATE_CHANGED   ─┐
  ├─ TYPE_WINDOW_CONTENT_CHANGED ─┼─▶ scanAndClick()
  └─ TYPE_WINDOWS_CHANGED        ─┘
        │
        ▼
   findCandidate()  ── BFS over node tree
        │
        ├─ P1  text + viewId match  (highest confidence)
        ├─ P2  text match only
        ├─ P3  viewId match only
        └─ P4  close-icon heuristic
              ├─ symbol / hint match
              ├─ ad-context keyword check
              └─ popup layout validation
        │
        ▼
   doClick()  ── ACTION_CLICK (or walk up to clickable parent)
```

### Safety Guards

| Guard | Description |
|-------|-------------|
| **Blacklist terms** | Rejects nodes containing payment / checkout keywords (支付, 购买, buy, checkout …) |
| **Package deny-list** | Never processes system UI, Settings, Play Store, or the app itself |
| **Per-window click cap** | Max 5 auto-clicks per foreground window |
| **Node dedup** | Same node is never clicked twice in one window session |
| **Throttle** | 800 ms minimum between consecutive clicks |
| **Ad-context requirement** | Close-icon path requires ad-related keywords nearby in the node tree |
| **Layout validation** | Close-icon path also checks popup proportions and dialog/modal ancestors |

---

## Features

- **Skip / Next detection** — recognizes "skip", "next", "跳过", "下一步" in node text and common view-ID patterns (`btn_skip`, `ad_skip`, …).
- **Smart close-button detection** — matches `×`, `✕`, `✖`, "close", "关闭" symbols and view-IDs, but only acts when ad-context keywords (广告, 廣告, 赞助, Sponsored, Banner …) are present nearby.
- **Popup layout heuristic** — validates that the close target sits inside a dialog/popup/modal overlay occupying 10–80 % of screen width and 10–60 % of height.
- **Package allowlist / blocklist** — default is blocklist mode (all apps minus deny-list). Switch to allowlist mode to restrict auto-skip to selected apps only.
- **App selector dialog** — searchable multi-choice list with Select All / Invert / Clear bulk actions.
- **Package editor** — free-text editor with deduplicate-and-sort and clear utilities.
- **Pause / resume** — tap the system accessibility button to toggle; launcher icon switches between active (purple) and paused (gray) states via activity-alias.
- **Overlay banner** — transient top banner shows pause/resume feedback.
- **Status notification** — persistent low-priority notification while the service is running.
- **Auto-save** — optional; saves rule changes immediately without pressing Save.
- **Multi-language** — English (default), Simplified Chinese, Traditional Chinese; auto-detects system locale.
- **Android 13+ notification permission** — requests `POST_NOTIFICATIONS` at runtime.

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml          # Permissions, activity-aliases (active/paused icons), service declaration
├── java/com/autonext/app/
│   ├── AutoNextService.kt       # Core accessibility service — event handling, BFS scan, click logic
│   └── MainActivity.kt          # Configuration UI — allowlist, app selector, package editor, status
└── res/
    ├── layout/
    │   ├── activity_main.xml         # Main settings screen
    │   ├── dialog_app_selector.xml   # Searchable app-picker dialog
    │   ├── dialog_package_editor.xml # Raw package-text editor dialog
    │   └── item_app_selector.xml     # Single row in the app-picker list
    ├── xml/
    │   └── accessibility_service_config.xml  # Accessibility service capabilities
    ├── values/strings.xml            # English strings
    ├── values-zh-rCN/strings.xml     # Simplified Chinese
    └── values-zh-rTW/strings.xml     # Traditional Chinese
```

---

## Requirements

### Device
- Android 8.0+ (API 26)

### Build Environment
- JDK 17+ (Eclipse Adoptium recommended)
- Android SDK — API 36
- Gradle 8.5.2+ (wrapper included)
- Android Gradle Plugin 8.5.2
- Kotlin 1.9.24

### Runtime Dependencies
| Library | Version |
|---------|---------|
| `androidx.core:core-ktx` | 1.13.1 |
| `androidx.appcompat:appcompat` | 1.7.0 |
| `com.google.android.material:material` | 1.12.0 |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 |

### Permissions
| Permission | Purpose |
|------------|---------|
| `QUERY_ALL_PACKAGES` | Enumerate installed apps for the selector dialog |
| `POST_NOTIFICATIONS` | Status notification (requested at runtime on Android 13+) |
| Accessibility Service | Must be enabled manually in **Settings → Accessibility** |

---

## Building

```bash
# Debug APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release APK (output renamed to ADgo.apk)
./gradlew assembleRelease
# → app/build/outputs/apk/release/ADgo.apk

# Clean + release
./gradlew clean assembleRelease

# Run unit tests
./gradlew test

# Install debug build on a connected device
./gradlew installDebug
```

### Build Configuration
| Setting | Value |
|---------|-------|
| Source / target compatibility | Java 1.8 |
| View Binding | Enabled |
| Proguard | Configured (release builds) |
| Signing | Debug keystore |

---

## Usage

1. Install the APK and open **ADgo**.
2. Tap **Enable Accessibility Service** → find "ADgo" in the list → enable it.
3. Return to the app — the status label shows **Enabled**.
4. *(Optional)* Switch to **allowlist mode** and pick which apps should be monitored.
5. Ads with Skip / Next / Close buttons will be dismissed automatically.
6. Use the **accessibility button** (system navigation bar) to pause / resume at any time.

---

## License

See repository for license details.

**File details:**
- Filename: `ADgo.apk`
- Size: Approximately 4-5 MB (varies by build)
- Signature: Debug keystone
- Architecture: Universal (supports ARM, ARM64, x86, x86_64)

## Installation

### On Physical Device
1. Enable **Developer Options** on your Android device (Settings → About → tap Build Number 7 times)
2. Enable **USB Debugging** (Settings → Developer Options → USB Debugging)
3. Connect device via USB
4. Run: `./gradlew installDebug` or `./gradlew installRelease`
5. Or manually transfer and install the APK file

### Enable Accessibility Service
1. Open the app
2. Tap **Enable Accessibility Service** button
3. Navigate to Settings → Accessibility → Services
4. Find and enable **ADgo**
5. Grant the required permissions

## Usage

### Main Screen

1. **Service Status**: Shows whether the Accessibility Service is currently enabled
2. **Allowlist Toggle**: Enable/disable the allowlist filtering mode
3. **Select Apps**: Choose which package names to automatically close popups for
4. **Edit Packages**: Manually add/remove/edit package names in bulk
5. **Service Control Button**: Quickly access Accessibility Service settings

### App Selection Dialog
- Search box to find apps by name
- Checkbox list to select/deselect multiple apps
- Selected apps appear at the top for quick access
- Vertical scrollbar for long lists

### Package Editor
- Edit package names line-by-line (one per line)
- **Normalize**: Removes duplicates and sorts alphabetically
- **Clear**: Removes all packages

### Configuration Modes

**Allowlist Mode** (Whitelist):
- Only close popups in selected apps
- All other apps are ignored

**Blocklist Mode** (Default):
- Close popups in all apps except blocklisted ones
- More permissive approach

### Auto-Save
- Enable to automatically persist changes to SharedPreferences
- Disable to manually click "Save Rules" button

## Architecture

### Core Components

**AutoNextService.kt** (Accessibility Service)
- Monitors accessibility events (TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED)
- Uses breadth-first search (BFS) to traverse accessibility tree
- Detects close buttons, skip buttons, and next buttons based on text and ID patterns
- Validates popup context for ad-related keywords before closing
- Shows notification bar status when active
- Package allowlist filtering

**MainActivity.kt** (Configuration UI)
- Manages UI state and user interactions
- Persists allowlist configuration to SharedPreferences
- App selection dialog with search filtering
- Service state detection via Settings.Secure API
- Dynamic button text based on service status
- Handles runtime permission requests for Android 13+

**Layouts**:
- `activity_main.xml` - Main UI with service status, allowlist controls, preview
- `dialog_app_selector.xml` - App selection dialog with search and ListView
- `dialog_package_editor.xml` - Package editing dialog with buttons
- `item_app_selector.xml` - List item layout for app selection

**Resources**:
- `strings.xml` - English text resources
- `strings-zh-rCN.xml` - Simplified Chinese resources
- `strings-zh-rTW.xml` - Traditional Chinese resources

### Data Flow
1. User configures allowlist in MainActivity
2. Configuration saved to SharedPreferences
3. Accessibility Service reads config on startup
4. Service monitors all window events on the device
5. When close button is detected with ad context, service clicks it
6. Notification bar shows real-time status

## Support & Troubleshooting

### Service Not Working
1. Verify Accessibility Service is enabled in Settings
2. Check that the app is not in battery optimization/doze mode
3. Grant all required permissions
4. Try restarting the device

### App List Not Showing
- Ensure `QUERY_ALL_PACKAGES` permission is granted
- Check if system has restricted package visibility (Android 11+)

### Popups Not Being Closed
1. Verify the popup contains ad-keywords (广告/赞助)
2. Check if the app is in the blocklist
3. Review logcat logs for permission or accessibility errors

### Language Not Changing
- The app auto-detects system locale on first launch
- Change system language in Settings → Language & Region
- Restart the app

## Development

### Project Structure
```
ADgo/
├── app/
│   ├── src/main/
│   │   ├── java/com/autonext/app/
│   │   │   ├── AutoNextService.kt    (Core accessibility service)
│   │   │   └── MainActivity.kt        (UI and configuration)
│   │   ├── res/
│   │   │   ├── layout/               (XML layouts)
│   │   │   ├── values/              (English strings)
│   │   │   ├── values-zh-rCN/       (Simplified Chinese)
│   │   │   └── values-zh-rTW/       (Traditional Chinese)
│   │   └── AndroidManifest.xml
│   ├── build.gradle                  (App-level build config)
│   └── proguard-rules.pro
├── build.gradle                      (Project-level build config)
├── gradle.properties                 (Gradle properties)
├── settings.gradle                   (Gradle settings)
├── gradlew                          (Gradle wrapper - macOS/Linux)
└── gradlew.bat                      (Gradle wrapper - Windows)
```

### Customization

**Add New Ad Keywords**:
Edit `AutoNextService.kt`, modify `AD_CONTEXT_HINTS` list:
```kotlin
private val AD_CONTEXT_HINTS = listOf("广告", "廣告", "赞助", "贊助", "promotion")
```

**Add New Close Button Patterns**:
Edit `AutoNextService.kt`, modify `CLOSE_HINTS` list:
```kotlin
private val CLOSE_HINTS = listOf("close", "关闭", "dismiss", "×", "x")
```

**Change Default Language**:
Modify system locale or edit string resources in `res/values/strings.xml`

## Version History

- **v1.0** (Current):
  - Initial release
  - Multi-language support (English, Simplified Chinese, Traditional Chinese)
  - Accessibility service with ad context validation
  - Package allowlist/blocklist filtering
  - App selection with search
  - Service status indicator
  - Notification bar status display

## License

 GPL

## Author

Tang Haoyu

## Support

For bug reports, feature requests, or questions, please mait to haoyu.tang@live.com.

---

**Last Updated**: 2026-04-27  
**Tested On**: Android 8.0 - Android 14 (API 26 - API 34)  
**Build Status**: ✅ Passing (No compilation errors)
