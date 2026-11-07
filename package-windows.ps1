<#
.SYNOPSIS
    Builds a native Windows package for the Arena desktop app.

.DESCRIPTION
    1. Compiles + packages the app with Maven (.\mvnw.cmd).
    2. Builds a trimmed runtime image via the javafx-maven-plugin (javafx:jlink).
    3. Runs jpackage to produce either a portable folder (app-image, no extra
       tools needed) or a real installer (msi / exe, requires WiX Toolset v3).
    4. Installer builds share a stable upgrade UUID; exe builds emit a SHA-256
       sidecar for publishing as a GitHub Release asset.

.EXAMPLE
    .\package-windows.ps1
    # -> portable app at target\dist\Arena\Arena.exe

.EXAMPLE
    .\package-windows.ps1 -Type msi -AppVersion 1.0.0
    # -> installer at target\dist\Arena-1.0.0.msi (needs WiX on PATH)
#>
[CmdletBinding()]
param(
    [ValidateSet("app-image", "msi", "exe")]
    [string]$Type = "app-image",
    [string]$AppVersion = "1.0.2"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $RepoRoot

function Require-Command([string]$Name, [string]$InstallHint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found. $InstallHint"
    }
}

Require-Command "java" "Install JDK 21+ and ensure 'java' is on PATH."
Require-Command "jpackage" "Install JDK 21+ (jpackage ships with the JDK) and ensure it is on PATH."

Write-Host "==> Building Arena desktop app ($Type) ..." -ForegroundColor Cyan

# 1) Compile + package the modular jar.
& .\mvnw.cmd clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }

# 2) Build a trimmed runtime image with the app bundled in.
#    (Output dir comes from <jlinkImageName> in pom.xml -> target\app)
& .\mvnw.cmd javafx:jlink
if ($LASTEXITCODE -ne 0) { throw "javafx:jlink failed." }

$RuntimeImage = Join-Path $RepoRoot "target\app"
if (-not (Test-Path $RuntimeImage)) { throw "Runtime image not found at $RuntimeImage" }

# 3) Optional installer icon. Windows needs .ico format — see README-DESKTOP.md.
$IconArg = @()
$IconPath = Join-Path $RepoRoot "src\main\resources\arena-icon.ico"
if (Test-Path $IconPath) {
    $IconArg = @("--icon", $IconPath)
} else {
    Write-Warning "No arena-icon.ico found — continuing with the default Java icon. See README-DESKTOP.md to add one."
}

if ($Type -ne "app-image") {
    Write-Host "Note: '$Type' installers require WiX Toolset v3 on PATH (https://wixtoolset.org)." -ForegroundColor Yellow
}

$Dest = Join-Path $RepoRoot "target\dist"
New-Item -ItemType Directory -Force -Path $Dest | Out-Null

# Keep this UUID unchanged between releases so Windows recognizes an upgrade
# as the same installed application (rather than a separate product).
$InstallerArgs = @()
if ($Type -ne "app-image") {
    $InstallerArgs = @(
        "--win-upgrade-uuid", "17e9d0d3-bfa7-4aee-928c-af43cbebcf2d"
    )
}

$JPackageArgs = @(
    "--type", $Type,
    "--name", "Arena",
    "--app-version", $AppVersion,
    "--java-options", "-Darena.app.version=$AppVersion",
    "--vendor", "O'Digital",
    "--description", "Arena desktop app - arena.ai on your Windows desktop.",
    "--copyright", "O'Digital",
    "--runtime-image", $RuntimeImage,
    "--module", "cm.odigital.arenaai/cm.odigital.arenaai.Launcher",
    "--dest", $Dest,
    "--win-menu",
    "--win-shortcut",
    "--win-dir-chooser"
) + $IconArg + $InstallerArgs

Write-Host "==> Running: jpackage $($JPackageArgs -join ' ')" -ForegroundColor Cyan
& jpackage @JPackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed." }

# The in-app updater refuses to launch an installer without a matching SHA-256.
if ($Type -eq "exe") {
    $InstallerPath = Join-Path $Dest "Arena-$AppVersion.exe"
    if (-not (Test-Path $InstallerPath)) { throw "Expected installer not found at $InstallerPath" }
    $InstallerHash = (Get-FileHash -Path $InstallerPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $ChecksumPath = "$($InstallerPath).sha256"
    "$InstallerHash  $(Split-Path -Leaf $InstallerPath)" | Set-Content -Path $ChecksumPath -Encoding ascii
    Write-Host "SHA-256 file: $ChecksumPath" -ForegroundColor Green
}

Write-Host ""
Write-Host "Done! Output is in target\dist" -ForegroundColor Green
if ($Type -eq "app-image") {
    Write-Host "Portable app: target\dist\Arena\Arena.exe"
}
