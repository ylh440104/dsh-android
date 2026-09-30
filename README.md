# DeepSeek Harness for Android

Unofficial Android port of DeepSeek Harness desktop application

Uses the original DeepSeek Harness runtime with a native Android WebView shell

## Features

- Full DeepSeek Harness runtime (unmodified)
- Platform account login (same as desktop)
- API key authentication
- All original tools and plugins
- WebView-based UI

## Build

Tag a release like `v0.2.0-rc.1` to trigger GitHub Actions

The workflow:
1 Downloads the original Windows installer
2 Extracts the Electron app.asar
3 Downloads Termux Node.js 22 for arm64
4 Packages runtime and node as tar.xz
5 Builds the APK
6 Publishes everything to GitHub Releases

## Architecture

APK -> downloads runtime on first launch -> spawns Node.js -> desktop-host web server on port 19387 -> WebView loads it

All original login flows work:
- DeepSeek Platform account sign-in (PKCE browser flow)
- API Key authentication
- Feishu test login (if configured)