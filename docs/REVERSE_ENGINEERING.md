# Reverse-engineering notes: Via Browser 7.4.1

## Targets
| File | SHA-256 (first 16) |
|---|---|
| Via_Browser_-_Fast___Light_7_4_1_APKPure.apk | 5764435e66cdfa71 |
| mark_via_gp_7_4_1-20261113_minAPI19_nodpi__apkmirror_com.apk | 6ee7c38fed6403f5 |

`classes.dex`, `resources.arsc`, `AndroidManifest.xml` and the non-META-INF file list are byte-identical
between the two. They differ only in packaging/signature data. All analysis below used the APKPure copy.

## Method
Static analysis only (nothing was executed): unzip inventory, manifest and resource parsing (androguard),
decompilation with jadx 1.5.1, string-table extraction from `resources.arsc` (1,079 default-locale strings,
86 locales), URL extraction from decompiled code. The code is obfuscated (single-letter package/class names),
so behaviour claims below come from manifest, resources and string evidence, not from reading logic.

## Observations
- Package `mark.via.gp`, versionName 7.4.1, versionCode 20261113, minSdk 19, targetSdk 36.
- No native libraries: the engine is Android System WebView.
- Activities: `Shell` (main, singleTask, PiP-capable), `Trampoline` (http/https/via:/file/content/inline VIEW handler),
  `Search` (PROCESS_TEXT, WEB_SEARCH), `CustomTab`.
- Services: `DownloadService` (foreground, data sync), `ReadAloudService` (foreground, media playback),
  `CustomTabsConnectionService`. Receivers: download, read-aloud, two search widgets.
- Packages (jadx): browser, common, data, download, gp, receiver, service, settings, widget.
- Bundled assets: error page, logo, a built-in filter list, a search-suggestion script, publicsuffix list.
- Embedded third-party: OkHttp, Kotlin, commonmark (Markdown), Firebase Analytics/Crashlytics, Google Play services.
- Permissions: camera, microphone, location, biometric, notifications, install packages, query all packages,
  foreground services, storage (legacy).
- Endpoints in code: Google/Microsoft translate endpoints; reverse-image-search URLs for Google, Google Lens, Bing,
  Yandex, TinEye, SauceNAO, Ascii2D, IQDB, trace.moe, Baidu, 360.
- Search engines in strings: Google, Bing, Baidu, DuckDuckGo, 360, plus custom URL.

## Feature coverage (evidence = string/manifest/URL found in the APK)
Legend: Done = implemented in v0.1, Partial, Missing.

| Via feature | Evidence | Nimbus v0.1 |
|---|---|---|
| Multiple tabs, close all/others | tab strings | Done (switcher, close, close all) |
| Incognito | "Incognito mode" | Partial (no history; cookies are NOT isolated, see limits) |
| Bookmarks, folders | "Bookmarks", "Choose a folder" | Partial (flat list, no folders) |
| Bookmark import/export | "Import/export bookmarks" | Done (Netscape HTML; Via's exact format unverified) |
| History, delete by range | "Delete history from", "The last hour" | Partial (no range delete) |
| Favorites/homepage tiles | "Favorites", "Add to homepage" | Partial (start page shows bookmarks) |
| Find in page | "Find", "Find next" | Done |
| Desktop site, UA presets | "Desktop site", UA list | Done (global presets + per-tab desktop) |
| Night mode / force dark | "Night mode", "Force dark" | Done |
| Ad blocking, built-in filters | "Enable built-in filters" | Partial (host list only) |
| ABP filter subscriptions | "Add filter subscription" | Missing |
| Mark-as-ad picker, custom filters | "Mark as ad" | Partial (block domain from link menu, custom host list) |
| Per-site configuration | "Enable site configuration" | Missing |
| JS/images/cookies/popups/DNT toggles | settings strings | Done (global) |
| Permissions: location, camera, mic | permission strings | Done (clipboard missing) |
| SSL warning | SSL strings | Done |
| Downloads | "Download manager" | Partial (system DownloadManager; no pause/resume UI, no categories) |
| Blob downloads | "Blob" error strings | Missing |
| Open external app prompt | "Allow website to open %1$s?" | Done |
| Context menu (link/image) | "Open in background", "Save image" | Done (not customizable) |
| View source, page info/certificate | "View source", "Certificate information" | Done (basic) |
| Save page / saved pages | "Save page" | Missing (print/PDF only) |
| Add to home screen | "Add to home screen" | Missing |
| Userscripts (Greasemonkey style), custom CSS | "New script", "Custom CSS" | Missing |
| Reader mode | "Hide reader", "Custom reader CSS" | Missing |
| Translate | translate endpoints | Missing |
| Reverse image search | 10 engine URLs | Missing |
| AI assistant | "AI settings", "API key" | Missing |
| Cloud sync / account | "Cloud data server", "Auto sync" | Missing |
| Password manager, CSV import/export | "Fill password" | Missing |
| Game mode, floating button, video gestures, back/forward gesture | strings | Missing |
| Toolbar auto-hide, tab bar, customizable menus | strings | Missing |
| Read aloud (TTS) | ReadAloudService | Missing |
| Picture-in-picture | "Picture mode", manifest PiP | Missing |
| Custom Tabs provider | CustomTab activity + service | Missing |
| Search widgets | 2 widget receivers | Missing |
| QR scan | "Tap the screen to grant camera permission to scan the code" | Missing |
| 86 locales | resources.arsc | Missing (English only) |
| Firebase analytics / Crashlytics | manifest + libs | Intentionally not replicated |

## Known limits
- Incognito: WebView shares one cookie jar, so incognito tabs do not isolate cookies. True isolation needs
  androidx.webkit profiles (recent WebView only).
- "Same UI" is approximated from the structure the strings imply (bottom bar: back, forward, menu, tabs, home).
  Pixel-level layout was not extracted from the compiled layout XML; icons, colors and branding are original.
- minSdk is 24 (Via supports 19).
