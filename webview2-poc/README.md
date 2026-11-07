# Arena WebView2 prototype

This is a standalone Windows proof of concept for Microsoft Edge WebView2. It is deliberately separate from the JavaFX application and installer so the embedded-browser engine can be tested before any production integration is attempted.

## Build

On Windows with the .NET 8 SDK installed:

```powershell
dotnet publish .\Arena.WebView2Prototype.csproj `
  -c Release -r win-x64 --self-contained true `
  -p:PublishSingleFile=true `
  -p:IncludeNativeLibrariesForSelfExtract=true `
  -o .\publish
```

The GitHub Actions workflow **WebView2 prototype** builds this as a self-contained `win-x64` application and uploads `Arena-WebView2-Prototype.zip` as a workflow artifact. The Microsoft Edge WebView2 Runtime is still required on the test PC; the app shows an error if it cannot initialize the runtime.

## Test

Extract the artifact and run `Arena.WebView2Prototype.exe`. It opens Arena by default. You can also pass an HTTP or HTTPS URL with `--url`, for example:

```powershell
.\Arena.WebView2Prototype.exe --url https://arena.ai/
```

The prototype uses WebView2's normal Edge/Chromium identity and its own profile at `%LOCALAPPDATA%\Arena\WebView2Prototype`. It does not spoof a user agent, import cookies from Chrome or Edge, or attempt to bypass or manipulate Cloudflare verification. Sign in separately if needed. Cloudflare may still challenge or block the embedded browser; this prototype is for testing, not a verified fix.

The prototype applies Windows Job Object limits to its process tree: a one-logical-core-equivalent hard CPU cap and a 1 GiB aggregate job-memory cap. It refuses to start if Windows cannot enforce them. These limits are separate from the Java JVM options used by Arena.

This artifact is not included in the Arena installer and does not replace the JavaFX view or system-browser fallback. If it passes the user's real-world test, the next step is to evaluate integration into Arena.
