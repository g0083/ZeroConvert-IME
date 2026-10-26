param(
    [ValidateSet('arm64-v8a', 'x86_64')][string]$Abi = 'arm64-v8a',
    [string]$AndroidNdk = "$env:LOCALAPPDATA/Android/Sdk/ndk/27.1.12297006",
    [string]$SwiftHome = "$env:LOCALAPPDATA/Programs/Swift",
    [string]$SwiftVersion = '6.4.1',
    [switch]$SwiftOnly
)
$ErrorActionPreference = 'Stop'
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
if (!(Test-Path -LiteralPath "$sdk/usr/lib/swift/android/$architecture/libswiftDispatch.so")) {
    & (Join-Path $PSScriptRoot 'prepare-android-swift-runtime.ps1') -SwiftVersion $SwiftVersion
}
if (Test-Path -LiteralPath $runtimeOverlay) {
    Get-ChildItem -LiteralPath $runtimeOverlay -Filter '*.so' | Copy-Item -Destination $out -Force
}
# Some Windows SDK bundles ship Dispatch as a static archive for arm64.
# Produce the shared runtime in our build directory, leaving the SDK untouched.
if (!(Test-Path -LiteralPath "$sdk/usr/lib/swift/android/$architecture/libdispatch.so") -and !(Test-Path -LiteralPath "$out/libdispatch.so")) {
    & "$ndkBin/clang++.exe" "--target=$architecture-linux-android26" --sysroot=$ndkSysroot -shared `
        '-Wl,-soname,libdispatch.so' '-Wl,--whole-archive' "$sdk/usr/lib/swift_static/android/$architecture/libdispatch.a" `
        '-Wl,--no-whole-archive' "-L$sdk/usr/lib/swift/android/$architecture" -lBlocksRuntime -llog -ldl -latomic `
        -o "$out/libdispatch.so"
    if ($LASTEXITCODE -ne 0) { throw 'Dispatch runtime link failed.' }
}
& node (Join-Path $PSScriptRoot 'prepare-shared-engine.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Shared source preparation failed.' }
& $cmake -S (Join-Path $repoRoot 'engine/native') -B $nativeBuild -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" `
    "-DCMAKE_TOOLCHAIN_FILE=$AndroidNdk/build/cmake/android.toolchain.cmake" "-DANDROID_ABI=$Abi" `
    '-DANDROID_PLATFORM=android-26' '-DANDROID_STL=c++_shared' '-DCMAKE_BUILD_TYPE=Release'
if ($LASTEXITCODE -ne 0) { throw 'Native Android configuration failed.' }
& $cmake --build $nativeBuild --target llama --parallel 6
if ($LASTEXITCODE -ne 0) { throw 'Native llama build failed.' }
$swiftFlags = @('-resource-dir', "$sdk/usr/lib/swift", '-tools-directory', $ndkBin,
    '-I', "$sdk/usr/include", '-Xcc', '-I', '-Xcc', "$sdk/usr/include",
    '-Xcc', '-resource-dir', '-Xcc', "$swiftBin/../lib/clang/21",
    '-Xcc', '-isystem', '-Xcc', "$ndkSysroot/usr/include/$architecture-linux-android",
    '-Xcc', "--sysroot=$ndkSysroot", '-Xclang-linker', "--sysroot=$ndkSysroot",
    '-L', "$nativeBuild/bin",
    '-L', $out,
    '-Xlinker', '--start-group', '-lllama', '-lggml', '-lggml-base', '-Xlinker', '--end-group')
@{
    version = 1; target = $triple; sdk = $sdk; 'toolchain-bin-dir' = $swiftBin
    'extra-swiftc-flags' = $swiftFlags
    'extra-cc-flags' = @("--sysroot=$ndkSysroot", "--target=$triple")
    'extra-cpp-flags' = @('-std=c++17')
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $destination -Encoding UTF8

# Use the exact revisions already resolved for Windows. Mirrors avoid downloading
# a second checkout, while never editing the existing dependency sources.
$pins = (Get-Content (Join-Path $repoRoot 'windows/server-swift/Package.resolved') -Raw | ConvertFrom-Json).pins
foreach ($pin in $pins) {
    $localCheckout = Join-Path $repoRoot "windows/server-swift/.build/checkouts/$($pin.identity)"
    if (Test-Path -LiteralPath $localCheckout) {
        & swift package --package-path $stage config set-mirror --original $pin.location --mirror ([uri]$localCheckout).AbsoluteUri
        if ($LASTEXITCODE -ne 0) { throw "Could not configure mirror for $($pin.identity)" }
    }
}
# Host cancellation around the existing llama call; no converter source rewrite.
$controlObject = Join-Path $nativeBuild 'inference_control.o'
& "$ndkBin/clang++.exe" "--target=$architecture-linux-android26" "--sysroot=$ndkSysroot" -std=c++17 -O3 -fPIC -c `
    (Join-Path $repoRoot 'engine/native/inference_control.cpp') `
    "-I$repoRoot/android/app/src/main/cpp/llama.cpp/include" "-I$repoRoot/android/app/src/main/cpp/llama.cpp/ggml/include" -o $controlObject
if ($LASTEXITCODE -ne 0) { throw 'Android inference cancellation adapter failed to compile.' }
& swift build --build-system native --package-path $stage --destination $destination -c release --product zerocon_swift `
    -Xlinker '--soname=libzerocon_swift.so' -Xlinker '--wrap=llama_decode' -Xlinker $controlObject -Xlinker '-lc++_shared'
if ($LASTEXITCODE -ne 0) { throw "Swift Android build failed for $Abi." }
New-Item -ItemType Directory -Force $out | Out-Null
$binaryRoot = Join-Path $stage ".build/$triple/release"
Copy-Item -LiteralPath (Join-Path $binaryRoot 'libzerocon_swift.so') -Destination $out
Get-ChildItem -LiteralPath "$sdk/usr/lib/swift/android/$architecture" -Filter '*.so' | Copy-Item -Destination $out
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
    & python (Join-Path $PSScriptRoot 'check-android-native.py') --prune-unused --abi $Abi
    if ($LASTEXITCODE -ne 0) { throw 'Android runtime dependency verification failed.' }
    $buildInputs = Join-Path $repoRoot 'engine/build/assets/shared-engine/sources.json'
    Copy-Item -LiteralPath $buildInputs -Destination (Join-Path $out 'build-inputs.json')
    Get-ChildItem -LiteralPath $out -Filter '*.so' | ForEach-Object {
        [ordered]@{ name = $_.Name; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant() }
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out 'libraries.json') -Encoding UTF8
}
Write-Output "Shared native engine built for $Abi. JNI libraries: $out"
