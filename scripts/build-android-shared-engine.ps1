param(
    [ValidateSet('arm64-v8a', 'x86_64')][string]$Abi = 'arm64-v8a',
    [string]$AndroidNdk = "$env:LOCALAPPDATA/Android/Sdk/ndk/27.1.12297006",
    [string]$SwiftHome = "$env:LOCALAPPDATA/Programs/Swift",
    [string]$SwiftVersion = '6.4.1',
    [switch]$SwiftOnly,
    # Optional speed-up: reuse the Windows checkouts instead of downloading.
    # Off by default so the build resolves from engine/Package.resolved alone.
    [switch]$UseLocalMirrors
)
Import-Module Microsoft.PowerShell.Utility -ErrorAction SilentlyContinue
$ErrorActionPreference = 'Stop'
function Get-Sha256Hex([string]$filePath) {
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($filePath)
    try {
        $hashBytes = $sha256.ComputeHash($stream)
        [System.BitConverter]::ToString($hashBytes).Replace('-', '').ToLowerInvariant()
    } finally {
        $stream.Dispose()
        $sha256.Dispose()
    }
}
$repoRoot = Split-Path $PSScriptRoot -Parent
$stage = Join-Path $repoRoot 'engine/build/swift'
$sdk = Join-Path $SwiftHome "Platforms/$SwiftVersion/Android.platform/Developer/SDKs/Android.sdk"
$swiftBin = Join-Path $SwiftHome "Toolchains/$SwiftVersion+Asserts/usr/bin"
$ndkBin = Join-Path $AndroidNdk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$ndkSysroot = Join-Path $AndroidNdk 'toolchains/llvm/prebuilt/windows-x86_64/sysroot'
$architecture = if ($Abi -eq 'arm64-v8a') { 'aarch64' } else { 'x86_64' }
$triple = "$architecture-unknown-linux-android26"
$destination = Join-Path $repoRoot "engine/build/destination-$Abi.json"
$out = Join-Path $repoRoot "engine/build/jniLibs/$Abi"
$nativeBuild = Join-Path $repoRoot "engine/build/native/$Abi"
$cmake = "$env:LOCALAPPDATA/Android/Sdk/cmake/3.22.1/bin/cmake.exe"
$ninja = "$env:LOCALAPPDATA/Android/Sdk/cmake/3.22.1/bin/ninja.exe"
foreach ($required in @($sdk, $swiftBin, $ndkBin)) {
    if (!(Test-Path -LiteralPath $required)) { throw "Missing native toolchain: $required" }
}
$env:PATH = (Join-Path $SwiftHome "Runtimes/$SwiftVersion/usr/bin") + ";$swiftBin;$ndkBin;" + $env:PATH
New-Item -ItemType Directory -Force $out | Out-Null
$runtimeOverlay = Join-Path $repoRoot "engine/build/runtime-overlay/$Abi"
$sdkRuntime = Join-Path $sdk "usr/lib/swift/android/$architecture"
$sdkDispatchStatic = Join-Path $sdk "usr/lib/swift_static/android/$architecture/libdispatch.a"
# One runtime build per ABI, never mixed: an archive libswiftCore with an SDK
# libFoundationEssentials segfaults in JSONDecoder. An SDK is complete only when
# it supplies Core, swiftDispatch and a shared Dispatch. A static libdispatch.a
# does not count: linking it yields a stub that exports only the referenced
# objects, so libswift_Concurrency.so cannot resolve _dispatch_main_q at runtime.
$sdkCore = Test-Path -LiteralPath (Join-Path $sdkRuntime 'libswiftCore.so')
$sdkSwiftDispatch = Test-Path -LiteralPath (Join-Path $sdkRuntime 'libswiftDispatch.so')
$sdkDispatch = Test-Path -LiteralPath (Join-Path $sdkRuntime 'libdispatch.so')
$sdkComplete = $sdkCore -and $sdkSwiftDispatch -and $sdkDispatch
$expectedSource = if ($sdkComplete) { 'sdk' } else { 'archive' }
# The extractor writes the chosen build to source.json; regenerate it whenever
# it disagrees with the installed SDK so a stale overlay cannot be applied.
$sourceManifest = Join-Path $runtimeOverlay 'source.json'
$overlaySource = $null
if (Test-Path -LiteralPath $sourceManifest) {
    $overlaySource = (Get-Content -LiteralPath $sourceManifest -Raw | ConvertFrom-Json).source
}
$overlayLibraries = @(Get-ChildItem -LiteralPath $runtimeOverlay -Filter '*.so' -ErrorAction SilentlyContinue)
if ($overlaySource -ne $expectedSource -or ($overlaySource -eq 'archive' -and $overlayLibraries.Count -eq 0)) {
    & (Join-Path $PSScriptRoot 'prepare-android-swift-runtime.ps1') -SwiftVersion $SwiftVersion
    $overlaySource = (Get-Content -LiteralPath $sourceManifest -Raw | ConvertFrom-Json).source
    $overlayLibraries = @(Get-ChildItem -LiteralPath $runtimeOverlay -Filter '*.so' -ErrorAction SilentlyContinue)
}
# Refuse to build rather than mix: the extractor must agree with the SDK that
# this script resolves, otherwise a -SwiftHome override would silently pair
# files from two different Swift builds.
if ($overlaySource -ne $expectedSource) {
    throw "Runtime source for $Abi is '$overlaySource' but $sdkRuntime needs '$expectedSource'."
}
$overlayArchive = $overlaySource -eq 'archive'
# Stage the archive set before linking so -lswiftDispatch resolves through
# $out: the installed x86_64 SDK imports it but ships no shared swiftDispatch.
# An SDK-complete ABI resolves it from the SDK, so staging stays archive-only.
if ($overlayArchive) {
    $overlayLibraries | Copy-Item -Destination $out -Force
}
# Compiling against the installed SDK's Foundation modules would also import
# foundation witness tables that the archive Foundation does not export, so an
# archive overlay must build against the archive module tree. Extract that tree
# next to the overlay, once per archive checksum, and build against it alone.
$archiveSdk = Join-Path $repoRoot "engine/build/swift-runtime-sdk/$Abi"
$archiveSwiftArch = if ($Abi -eq 'arm64-v8a') { 'aarch64' } else { 'x86_64' }
$archiveBundle = Join-Path $repoRoot '.local/toolchains/swift-6.4.1-RELEASE_android.artifactbundle.tar.gz'
$archiveSha = $null
$archiveSdkChanged = $false
if ($overlayArchive) {
    if (!(Test-Path -LiteralPath $archiveBundle)) {
        & (Join-Path $PSScriptRoot 'prepare-android-swift-runtime.ps1') -SwiftVersion $SwiftVersion
        if (!(Test-Path -LiteralPath $archiveBundle)) { throw 'Swift Android runtime archive is unavailable.' }
    }
    $archiveSha = Get-Sha256Hex $archiveBundle
    $archiveMarker = Join-Path $archiveSdk '.extracted'
    $extractedSha = if (Test-Path -LiteralPath $archiveMarker) { (Get-Content -LiteralPath $archiveMarker -Raw).Trim() } else { '' }
    if ($extractedSha -ne $archiveSha) {
        # Replace the whole tree so a partially extracted or older payload can
        # never be mixed into the build.
        if (Test-Path -LiteralPath $archiveSdk) { Remove-Item -LiteralPath $archiveSdk -Recurse -Force }
        New-Item -ItemType Directory -Force $archiveSdk | Out-Null
        Push-Location $archiveSdk
        try {
            & tar -xzf $archiveBundle --strip-components 3 `
                "*/swift-android/swift-resources/usr/lib/swift-$archiveSwiftArch/*" `
                '*/swift-android/swift-resources/usr/include/*'
            $extractExit = $LASTEXITCODE
        } finally {
            Pop-Location
        }
        if ($extractExit -ne 0) { throw 'Archive Swift SDK extraction failed.' }
        # The payload's relative "clang" link points outside this subtree; keep
        # the extracted tree free of dangling links so re-extraction is safe.
        $clangLink = Join-Path $archiveSdk "usr/lib/swift-$archiveSwiftArch/clang"
        if (Test-Path -LiteralPath $clangLink) {
            $clangItem = Get-Item -LiteralPath $clangLink -Force
            if ($clangItem.LinkType) { [System.IO.File]::Delete($clangItem.FullName) }
        }
        foreach ($required in @('android/Foundation.swiftmodule', 'android/Swift.swiftmodule', 'android/libswiftDispatch.so')) {
            if (!(Test-Path -LiteralPath (Join-Path $archiveSdk "usr/lib/swift-$archiveSwiftArch/$required"))) {
                throw "Archive Swift SDK is missing usr/lib/swift-$archiveSwiftArch/$required."
            }
        }
        $archiveSdkChanged = $true
    }
    # The driver takes swiftrt.o from the -sdk the destination names, so give
    # the archive tree that SDK layout using the archive's own object.
    $archiveShim = Join-Path $archiveSdk "usr/lib/swift/android/$architecture"
    New-Item -ItemType Directory -Force $archiveShim | Out-Null
    $swiftRuntimeObject = Join-Path $archiveSdk "usr/lib/swift-$archiveSwiftArch/android/$architecture/swiftrt.o"
    if (Test-Path -LiteralPath $swiftRuntimeObject) {
        Copy-Item -LiteralPath $swiftRuntimeObject -Destination $archiveShim -Force
    }
}
& node (Join-Path $PSScriptRoot 'prepare-shared-engine.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Shared source preparation failed.' }
& $cmake -S (Join-Path $repoRoot 'engine/native') -B $nativeBuild -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" `
    "-DCMAKE_TOOLCHAIN_FILE=$AndroidNdk/build/cmake/android.toolchain.cmake" "-DANDROID_ABI=$Abi" `
    '-DANDROID_PLATFORM=android-26' '-DANDROID_STL=c++_shared' '-DCMAKE_BUILD_TYPE=Release'
if ($LASTEXITCODE -ne 0) { throw 'Native Android configuration failed.' }
& $cmake --build $nativeBuild --target llama --parallel 6
if ($LASTEXITCODE -ne 0) { throw 'Native llama build failed.' }
if ($overlayArchive) {
    # Archive module search paths come first so they win over the SDK copies:
    # the installed SDK modules are a different Swift build than the overlay.
    $archiveSwiftLib = "$archiveSdk/usr/lib/swift-$archiveSwiftArch"
    $swiftFlags = @('-resource-dir', $archiveSwiftLib, '-tools-directory', $ndkBin,
        '-I', "$archiveSwiftLib/android",
        '-I', $archiveSwiftLib,
        '-I', "$archiveSdk/usr/include",
        '-Xcc', '-I', '-Xcc', $archiveSwiftLib,
        '-Xcc', '-I', '-Xcc', "$archiveSdk/usr/include",
        '-Xcc', '-resource-dir', '-Xcc', "$swiftBin/../lib/clang/21",
        '-Xcc', '-isystem', '-Xcc', "$ndkSysroot/usr/include/$architecture-linux-android",
        '-Xcc', "--sysroot=$ndkSysroot", '-Xclang-linker', "--sysroot=$ndkSysroot",
        '-L', "$nativeBuild/bin",
        '-L', $out,
        '-Xlinker', '--start-group', '-lllama', '-lggml', '-lggml-base', '-Xlinker', '--end-group')
} else {
    $swiftFlags = @('-resource-dir', "$sdk/usr/lib/swift", '-tools-directory', $ndkBin,
        '-I', "$sdk/usr/include", '-Xcc', '-I', '-Xcc', "$sdk/usr/include",
        '-Xcc', '-resource-dir', '-Xcc', "$swiftBin/../lib/clang/21",
        '-Xcc', '-isystem', '-Xcc', "$ndkSysroot/usr/include/$architecture-linux-android",
        '-Xcc', "--sysroot=$ndkSysroot", '-Xclang-linker', "--sysroot=$ndkSysroot",
        '-L', "$nativeBuild/bin",
        '-L', $out,
        '-Xlinker', '--start-group', '-lllama', '-lggml', '-lggml-base', '-Xlinker', '--end-group')
}
# An archive overlay names its own payload as the SDK so the Swift driver picks
# the archive's swiftrt.o and headers rather than the other build's copies.
$destinationSdk = if ($overlayArchive) { $archiveSdk } else { $sdk }
@{
    version = 1; target = $triple; sdk = $destinationSdk; 'toolchain-bin-dir' = $swiftBin
    'extra-swiftc-flags' = $swiftFlags
    'extra-cc-flags' = @("--sysroot=$ndkSysroot", "--target=$triple")
    'extra-cpp-flags' = @('-std=c++17')
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $destination -Encoding UTF8

# Dependencies resolve from engine/Package.resolved, which is committed and kept
# equal to the Windows pins by scripts/check-shared-engine-pins.mjs. The Windows
# checkouts are only an optional download shortcut, never the source of truth.
$mirrorConfig = Join-Path $stage '.swiftpm/configuration/mirrors.json'
if ($UseLocalMirrors) {
    $pins = (Get-Content (Join-Path $repoRoot 'engine/Package.resolved') -Raw | ConvertFrom-Json).pins
    foreach ($pin in $pins) {
        $localCheckout = Join-Path $repoRoot "windows/server-swift/.build/checkouts/$($pin.identity)"
        if (Test-Path -LiteralPath $localCheckout) {
            # The working checkout carries the pinned revision but not always the
            # newer tags. Import the full tag set from the local bare mirror so the
            # version requirements from Package.resolved resolve without a download.
            & git -C $localCheckout fetch --tags origin --quiet
            & swift package --package-path $stage config set-mirror --original $pin.location --mirror ([uri]$localCheckout).AbsoluteUri
            if ($LASTEXITCODE -ne 0) { throw "Could not configure mirror for $($pin.identity)" }
        }
    }
} elseif (Test-Path -LiteralPath $mirrorConfig) {
    # A mirror left by an earlier run would silently override the committed pins.
    # This file lives in the generated staging tree, never in repository sources.
    Remove-Item -LiteralPath $mirrorConfig -Force
}
# Host cancellation around the existing llama call; no converter source rewrite.
$controlObject = Join-Path $nativeBuild 'inference_control.o'
& "$ndkBin/clang++.exe" "--target=$architecture-linux-android26" "--sysroot=$ndkSysroot" -std=c++17 -O3 -fPIC -c `
    (Join-Path $repoRoot 'engine/native/inference_control.cpp') `
    "-I$repoRoot/android/app/src/main/cpp/llama.cpp/include" "-I$repoRoot/android/app/src/main/cpp/llama.cpp/ggml/include" -o $controlObject
if ($LASTEXITCODE -ne 0) { throw 'Android inference cancellation adapter failed to compile.' }
# A freshly extracted archive runtime invalidates every object of this target:
# the previous ones were compiled against another Swift build. Dependency
# checkouts, repositories and artifacts stay so the resolve keeps working.
$forceCleanTarget = $overlayArchive -and $archiveSdkChanged
if ($forceCleanTarget) {
    # Removing the per-target release/debug trees also removes their *.build
    # object dirs. checkouts/repositories/artifacts stay resolvable offline.
    $targetDir = Join-Path $stage ".build/$triple"
    foreach ($product in @((Join-Path $targetDir 'release'), (Join-Path $targetDir 'debug'))) {
        if (Test-Path -LiteralPath $product) { Remove-Item -LiteralPath $product -Recurse -Force }
    }
    # Any remaining per-target object dirs at either level go too; checkouts,
    # repositories and artifacts are never matched by this filter.
    $objectDirs = @(Get-ChildItem -LiteralPath (Join-Path $stage '.build') -Directory -Filter '*.build' -ErrorAction SilentlyContinue) +
        @(Get-ChildItem -LiteralPath $targetDir -Directory -Filter '*.build' -ErrorAction SilentlyContinue)
    foreach ($objectDir in $objectDirs) { Remove-Item -LiteralPath $objectDir.FullName -Recurse -Force }
    # SwiftPM exposes the last build as .build/<config>. Drop only links that
    # this clean just invalidated; a real directory is never touched.
    foreach ($config in @('release', 'debug')) {
        $linkPath = Join-Path $stage ".build/$config"
        if (!(Test-Path -LiteralPath $linkPath)) { continue }
        $linkItem = Get-Item -LiteralPath $linkPath -Force
        if ($linkItem.LinkType) { [System.IO.Directory]::Delete($linkItem.FullName, $false) }
    }
}
Write-Output $(if ($forceCleanTarget) { "Forced clean Swift build for ${Abi}: runtime build changed." } else { "Swift build for ${Abi} reuses existing objects: runtime build unchanged." })
& swift build --build-system native --package-path $stage --destination $destination -c release --product zerocon_swift `
    -Xlinker '--soname=libzerocon_swift.so' -Xlinker '--wrap=llama_decode' -Xlinker $controlObject -Xlinker '-lc++_shared'
if ($LASTEXITCODE -ne 0) { throw "Swift Android build failed for $Abi." }
if ($overlayArchive) { Set-Content -LiteralPath (Join-Path $archiveSdk '.extracted') -Value $archiveSha -Encoding ascii }
New-Item -ItemType Directory -Force $out | Out-Null
$binaryRoot = Join-Path $stage ".build/$triple/release"
Copy-Item -LiteralPath (Join-Path $binaryRoot 'libzerocon_swift.so') -Destination $out
$sdkRuntimeLibraries = @(Get-ChildItem -LiteralPath $sdkRuntime -Filter '*.so')
$sdkRuntimeLibraries | Copy-Item -Destination $out
# For an incomplete ABI the archive set replaces the SDK runtime wholesale: drop
# the SDK-only files it does not provide, then lay down every archive file last,
# so $out holds exactly one build per ABI. An SDK-complete ABI keeps the SDK
# copy alone.
if ($overlayArchive) {
    $overlayNames = @{}
    foreach ($library in $overlayLibraries) { $overlayNames[$library.Name] = $true }
    foreach ($library in $sdkRuntimeLibraries) {
        if (!$overlayNames.ContainsKey($library.Name)) { Remove-Item -LiteralPath (Join-Path $out $library.Name) -Force }
    }
    $overlayLibraries | Copy-Item -Destination $out -Force
}
Get-ChildItem -LiteralPath $binaryRoot -Directory -Filter '*.resources' | Copy-Item -Destination (Join-Path $repoRoot 'engine/build/assets/shared-engine') -Recurse -Force
if (!$SwiftOnly) {
    $rustTarget = "$architecture-linux-android"
    $targetKey = $rustTarget.ToUpperInvariant().Replace('-', '_')
    [Environment]::SetEnvironmentVariable("CARGO_TARGET_${targetKey}_LINKER", "$ndkBin/$architecture-linux-android26-clang.cmd", 'Process')
    & cargo build --manifest-path (Join-Path $repoRoot 'engine/rust/Cargo.toml') --locked --release --target $rustTarget
    if ($LASTEXITCODE -ne 0) { throw "Rust Android build failed. Install target with: rustup target add $rustTarget" }
    & $cmake --build $nativeBuild --target zenz_jni --parallel 6
    if ($LASTEXITCODE -ne 0) { throw 'Shared JNI build failed.' }
    Copy-Item -LiteralPath (Join-Path $nativeBuild 'libzenz_jni.so') -Destination $out
    # Upstream runtime CPU variants are loaded with dlopen, so DT_NEEDED alone
    # cannot discover them. Copy all backends produced by this build explicitly.
    $backendLibraries = @()
    foreach ($libraryPath in (Get-Content (Join-Path $nativeBuild 'runtime-libraries.txt'))) {
        if (!$libraryPath) { continue }
        Copy-Item -LiteralPath $libraryPath -Destination $out -Force
        if ((Split-Path $libraryPath -Leaf) -like 'libggml-cpu*.so') { $backendLibraries += (Split-Path $libraryPath -Leaf) }
    }
    ConvertTo-Json -InputObject $backendLibraries | Set-Content -LiteralPath (Join-Path $out 'cpu-backends.json') -Encoding UTF8
    Copy-Item -LiteralPath "$ndkSysroot/usr/lib/$architecture-linux-android/libc++_shared.so" -Destination $out
    # Verify the packaged dependency closure and Swift symbol coverage after
    # the runtime set is final, then prune only unreachable libraries inside
    # this generated folder; the checker refuses linked or out-of-tree paths.
    & python (Join-Path $PSScriptRoot 'check-android-native.py') --prune-unused --skip-symbol-check --abi $Abi
    if ($LASTEXITCODE -ne 0) { throw 'Android runtime prune failed.' }
    # Pruning runs first, then coherence is enforced on the final set, so a
    # build can never finish with unresolved Swift runtime symbols.
    & python (Join-Path $PSScriptRoot 'check-android-native.py') --abi $Abi
    if ($LASTEXITCODE -ne 0) { throw 'Android Swift runtime verification failed.' }
    $buildInputs = Join-Path $repoRoot 'engine/build/assets/shared-engine/sources.json'
    Copy-Item -LiteralPath $buildInputs -Destination (Join-Path $out 'build-inputs.json')
    Get-ChildItem -LiteralPath $out -Filter '*.so' | ForEach-Object {
        [ordered]@{ name = $_.Name; sha256 = (Get-Sha256Hex $_.FullName) }
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'libraries.json') -Encoding UTF8
}
Write-Output "Shared native engine built for $Abi. JNI libraries: $out"
