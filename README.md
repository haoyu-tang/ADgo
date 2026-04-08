# ADgo - Automatic Ad Popup Dismisser

ADgo is an Android accessibility service application that automatically detects and dismisses popup advertisements by clicking the close button. It uses intelligent context analysis to ensure that only legitimate ad popups are closed.

## Features

- **Automatic Popup Detection**: Uses Android Accessibility Service to monitor window content changes and detect popup windows
- **Smart Ad Detection**: Only closes popups that contain ad-related keywords (广告/廣告/赞助/贊助 - ad/sponsor)
- **Smart Close Button Detection**: Recognizes various close button patterns (x, ×, ✕, ✖ symbols and close-related text/IDs)
- **Package Allowlist/Blocklist**: Configure which apps should have popups automatically closed
  - Allowlist mode: Only close popups in selected apps
  - Blocklist mode (default): Close popups in all apps except those on the blocklist
- **Search Functionality**: Quick search to find and select apps from the installed applications list
- **Package Editor**: Normalize and clear package lists with bulk operations
- **Dynamic App Sorting**: Selected apps appear at the top of the app selection list
- **Multi-Language Support**:
  - English (default)
  - Simplified Chinese (中文简体)
  - Traditional Chinese (中文繁體)
  - Auto-detects system language
- **Service Status Indicator**: 
  - Display status in main UI (Enabled/Disabled)
  - Notification bar status indicator shows when the service is active
- **Auto-Save**: Option to automatically save configuration changes
- **Notification Permission**: Properly handles runtime permissions for Android 13+

## Requirements

### System Requirements
- **Android SDK**: Compiled with SDK 36, supports devices running Android 8.0+ (minSdk 26)
- **Java/Kotlin**: JDK 17+ (Eclipse Adoptium recommended)
- **Gradle**: 8.5.2+

### Build Dependencies
- Android Gradle Plugin: 8.5.2
- Kotlin: 1.9.24
- Android SDK 36

### Runtime Dependencies (AndroidX/Google)
- androidx.core:core-ktx:1.13.1
- androidx.appcompat:appcompat:1.7.0
- com.google.android.material:material:1.12.0
- androidx.constraintlayout:constraintlayout:2.1.4

### Required Permissions
- `android.permission.QUERY_ALL_PACKAGES` - Query all installed applications
- `android.permission.POST_NOTIFICATIONS` - Show status notifications (Android 13+)
- Accessibility Service permission - Must be manually enabled in device Settings

## Building

### Prerequisites
1. Install JDK 17+ (Eclipse Adoptium or Oracle JDK)
2. Install Android SDK (API 36) via Android Studio SDK Manager
3. Configure gradle.properties with your JDK path (if not set)

### Build Commands

**Debug APK** (development build):
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

**Release APK** (optimized build):
```bash
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/ADgo.apk`

**Full Clean Build**:
```bash
./gradlew clean assembleRelease
```

**Run Tests**:
```bash
./gradlew test
```

**Install on Connected Device** (debug):
```bash
./gradlew installDebug
```

### Build Configuration
- Source compatibility: Java 1.8
- Target compatibility: Java 1.8
- View Binding: Enabled
- Proguard: Enabled (release builds)
- Signing: Uses debug keystore

## Release APK Location

After building with `./gradlew assembleRelease`, the release APK is located at:

```
app/build/outputs/apk/release/ADgo.apk
```

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

**Last Updated**: 2026-04-08  
**Tested On**: Android 8.0 - Android 14 (API 26 - API 34)  
**Build Status**: ✅ Passing (No compilation errors)
