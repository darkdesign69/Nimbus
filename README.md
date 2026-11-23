# Nimbus Browser

A lightweight Android browser built on the system WebView, with a feature set derived from
a reverse-engineering study of Via Browser 7.4.1 (see [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md)).
It is a clean-room implementation: no decompiled code, icons, strings tables or filter lists were copied.

## Status: v0.1 (core browser)
Tabs, incognito tabs, address bar with search/URL detection, 7 search engines + custom URL,
bookmarks and history (with HTML import/export), find in page, desktop site, night mode,
ad blocking (host based) with custom filters, per-feature privacy toggles, downloads,
file upload, fullscreen video, permission prompts, SSL warnings, view source, print/PDF.
The full matrix of what is and is not done yet is in the docs.

**Not verified on a device yet.** The code was written without an Android toolchain available;
the first CI run is the first compile. Expect to fix a few build errors.

## Build
Create a repo and add the two workflow files (`apply-and-build.yml`, `release.yml`) under `.github/workflows/`.
Then upload `nimbus-full.zip` to the repo root: the workflow unpacks it, commits the files, and builds a debug APK.
Later patches: upload any other `*.zip` to the root and it is applied on top (a name containing "full" replaces the whole project).

To publish a release, run the `Make Release` workflow from the Actions tab and enter a tag such as `v0.1.0`.

Local: `gradle assembleDebug` (JDK 17, Android SDK 34, Gradle 8.9+).

### Signing
Add repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` to sign releases
with your own key. Without them releases use the debug key.
