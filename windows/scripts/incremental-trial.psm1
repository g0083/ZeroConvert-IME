Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'distribution.psm1')

function Get-TrialInputGroups {
    [ordered]@{
        rust = @('windows/Cargo.toml','windows/Cargo.lock','windows/.cargo','windows/app-version.json')
        client = @('windows/crates/client')
        shared = @('windows/crates/shared')
        macros = @('windows/crates/macros')
        selection_ui = @('windows/crates/selection-ui')
        server = @('windows/crates/server/src','windows/crates/server/Cargo.toml','windows/crates/server/build.rs')
        keyboard = @('windows/native/keyboard-prior','windows/scripts/build-keyboard-prior.ps1')
        runtime = @('windows/scripts/prepare-modern-llama.py','windows/scripts/converter_patch.py','windows/scripts/modern-llama.ps1','patches/llama.cpp/zerocon-compat-b12125.patch')
        'translation-model' = @('artifacts/models/plamo-3-translate-0.8b/plamo-3-translate-0.8b-Q4_K_M.gguf','artifacts/models/plamo-3-translate-2b/plamo-3-translate-2b-Q4_K_M.gguf')
        ui = @('windows/crates/ui/src','windows/crates/ui/Cargo.toml','windows/crates/ui/build.rs')
        launcher = @('windows/crates/launcher/src','windows/crates/launcher/Cargo.toml','windows/crates/launcher/build.rs')
        frontend = @('windows/frontend/src','windows/frontend/public','windows/frontend/index.html',
            'windows/frontend/vite.config.ts','windows/frontend/tsconfig.json','windows/frontend/tsconfig.node.json',
            'windows/frontend/package.json','windows/frontend/package-lock.json','windows/frontend/src-tauri/src',
            'windows/frontend/src-tauri/build.rs','windows/frontend/src-tauri/Cargo.toml',
            'windows/frontend/src-tauri/tauri.conf.json','windows/frontend/src-tauri/capabilities','windows/frontend/src-tauri/icons',
            'licenses/third-party.json','data/user-dictionary-pos.json','windows/deployment/USE-TERMS.txt','windows/deployment/LICENSE-Apache-2.0.txt',
            'windows/deployment/LICENSE-Swift.txt','windows/deployment/LICENSE-Unicode.txt','windows/deployment/THIRD_PARTY_LICENSES.md',
            'windows/deployment/THIRD_PARTY_RUST.md','windows/deployment/THIRD_PARTY_FRONTEND.md','data/dictionary/AdditionalDictionaryNotices')
        swift = @('windows/server-swift/Sources','windows/server-swift/Package.swift','windows/server-swift/Package.resolved')
        dictionary = @('windows/server-swift/azooKey_dictionary_storage/Dictionary','windows/scripts/generate-reverse-dictionary.mjs',
            'scripts/prepare_dictionary.py','data/dictionary')
        emoji = @('windows/server-swift/azooKey_emoji_dictionary_storage/EmojiDictionary')
        deployment = @('windows/deployment')
        models = @('artifacts/models/zerocon/zerocon-standard-v4.2b-iq4nl-emb8.gguf','artifacts/models/zerocon/zerocon-standard-v4.2b-q8_0.gguf','artifacts/models/zerocon/zerocon-nano-v4.2b-q8_0.gguf','artifacts/models/zerocon/zerocon-nano-v4.2b-iq4nl-emb8.gguf')
        tooling = @('windows/scripts/build-trial.ps1','windows/scripts/incremental-trial.psm1')
    }
}

function Read-AdditionalTrialDictionary([string]$Directory, [string]$BaseRoot) {
    $directoryPath = (Resolve-Path -LiteralPath $Directory).ProviderPath
    $manifestPath = Get-ArtifactPath $directoryPath 'additional-dictionary.json'
    $dictionaryPath = Get-ArtifactPath $directoryPath 'Dictionary'
    $manifest = Get-Content -LiteralPath $manifestPath -Encoding UTF8 -Raw | ConvertFrom-Json
    if ($manifest.schemaVersion -ne 1 -or $manifest.kind -ne 'additional-trial-dictionary') {
        throw 'Invalid additional trial dictionary manifest.'
    }
    $baseRevision = & git -C $BaseRoot rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $baseRevision -cne $manifest.baseRevision) {
        throw 'Additional dictionary was generated for a different standard dictionary revision.'
    }
    foreach ($mandatory in @('louds/charID.chid','mm.binary','Reverse/reverse-v2.bin')) {
        if ($mandatory -cnotin @($manifest.files.path)) { throw "Additional dictionary is missing $mandatory." }
    }
    Test-ArtifactEntries $dictionaryPath $manifest.files
    if (@(Get-ArtifactEntries $dictionaryPath -NamesOnly).Count -ne @($manifest.files).Count) {
        throw 'Unexpected files in additional trial dictionary.'
    }
    $manifest.files
}

function Get-TrialInputSnapshot([string]$Root, $Previous = $null, [string]$AdditionalDictionaryDirectory, [switch]$DictionaryOnly, [switch]$BaseDictionary) {
    $groups = Get-TrialInputGroups
    $result = [ordered]@{}
    foreach ($group in $groups.Keys) {
        if ($DictionaryOnly -and $group -ne 'dictionary') { continue }
        $prior = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
        if ($Previous -and $Previous.PSObject.Properties[$group]) {
            foreach ($entry in $Previous.$group.files) { $prior[$entry.path] = $entry }
        }
        $files = New-Object 'Collections.Generic.List[object]'
        $toHash = New-Object 'Collections.Generic.List[object]'
        foreach ($relative in $groups[$group]) {
            if ($relative -eq 'data/dictionary' -and ($BaseDictionary -or $AdditionalDictionaryDirectory)) { continue }
            $inputPath = Join-Path $Root $relative
            if (-not (Test-Path -LiteralPath $inputPath)) { continue }
            $items = if (Test-Path -LiteralPath $inputPath -PathType Container) {
                Get-ChildItem -LiteralPath $inputPath -Recurse -File | Where-Object { $_.FullName -notmatch '[\\/](target|node_modules|\.git|gen)[\\/]' }
            } else { Get-Item -LiteralPath $inputPath }
            foreach ($item in $items) {
                if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Source link is unsupported: $($item.FullName)" }
                $name = $item.FullName.Substring($Root.TrimEnd('\').Length + 1).Replace('\','/')
                $ticks = $item.LastWriteTimeUtc.Ticks.ToString()
                # Large data directories use a stat cache; ordinary source is always content-hashed.
                $cached = $group -in @('dictionary','emoji','models','translation-model') -and $prior.ContainsKey($name) -and
                    $prior[$name].bytes -eq $item.Length -and $prior[$name].ticks -eq $ticks
                $entry = [pscustomobject]@{ path=$name; bytes=$item.Length; ticks=$ticks; sha256=$(if ($cached) { $prior[$name].sha256 } else { $null }) }
                $files.Add($entry)
                if (-not $cached) {
                    $toHash.Add([pscustomobject]@{ entry=$entry; fullName=$item.FullName })
                }
            }
        }
        if ($toHash.Count -gt 0) {
            [string[]]$paths = @($toHash | ForEach-Object { $_.fullName })
            $hashes = [ZeroConArtifactCrypto]::HashFilesParallel($paths)
            for ($i = 0; $i -lt $toHash.Count; $i++) {
                $toHash[$i].entry.sha256 = $hashes[$i]
            }
        }
        if ($group -eq 'dictionary' -and $AdditionalDictionaryDirectory) {
            foreach ($entry in (Read-AdditionalTrialDictionary $AdditionalDictionaryDirectory (Join-Path $Root 'windows/server-swift/azooKey_dictionary_storage'))) {
                $files.Add([pscustomobject]@{
                    path='additional-dictionary/'+$entry.path; bytes=$entry.bytes; sha256=$entry.sha256
                })
            }
        }
        [string[]]$lines = @($files.ToArray() | ForEach-Object { $_.path + ':' + $_.sha256 })
        [Array]::Sort($lines, [StringComparer]::Ordinal)
        $algorithm = [Security.Cryptography.SHA256]::Create()
        try { $hash = [BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes(($lines -join "`n")))).Replace('-','').ToLowerInvariant() }
        finally { $algorithm.Dispose() }
        $result[$group] = [pscustomobject]@{ hash=$hash; files=$files.ToArray() }
    }
    [pscustomobject]$result
}

function Get-TrialBuildPlan([string[]]$Changed) {
    $selected = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($group in $Changed) {
        switch ($group) {
            { $_ -in @('rust','shared') } { foreach ($part in @('client','server','ui','launcher','frontend')) { $null=$selected.Add($part) } }
            'macros' { $null=$selected.Add('client') }
            'selection_ui' { foreach ($part in @('client','ui')) { $null=$selected.Add($part) } }
            'server' { foreach ($part in @('server','ui','frontend')) { $null=$selected.Add($part) } }
            'swift' { $null=$selected.Add('swift') }
            'runtime' { foreach ($part in @('runtime','swift','keyboard','server','launcher')) { $null=$selected.Add($part) } }
            'deployment' { $null=$selected.Add('deployment'); $null=$selected.Add('models') }
            default { $null=$selected.Add($group) }
        }
    }
    @($selected | Sort-Object)
}

function Get-TrialInputChanges($Before, $After) {
    foreach ($group in $After.PSObject.Properties) {
        $old = $Before.PSObject.Properties[$group.Name]
        if ($old -and $old.Value.hash -eq $group.Value.hash) { continue }
        $files = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
        if ($old) { foreach ($entry in $old.Value.files) { $files[$entry.path] = $entry.sha256 } }
        $paths = New-Object 'Collections.Generic.List[string]'
        foreach ($entry in $group.Value.files) {
            if (-not $files.ContainsKey($entry.path) -or $files[$entry.path] -ne $entry.sha256) { $paths.Add($entry.path) }
            $null = $files.Remove($entry.path)
        }
        foreach ($path in $files.Keys) { $paths.Add($path) }
        [pscustomobject]@{ group=$group.Name; paths=$paths.ToArray() }
    }
}

function Read-TrialUpdate([string]$Directory) {
    $directoryPath = (Resolve-Path -LiteralPath $Directory).ProviderPath
    $manifestPath = Get-ArtifactPath $directoryPath 'update-manifest.json'
    $manifest = Get-Content -Encoding UTF8 -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    if ($manifest.schemaVersion -ne 1 -or $manifest.kind -ne 'incremental-trial') { throw 'Invalid incremental trial update.' }
    $payload = Get-ArtifactPath $directoryPath 'payload'
    Test-ArtifactEntries $payload $manifest.files
    if (@(Get-ArtifactEntries $payload -NamesOnly).Count -ne @($manifest.files).Count) { throw 'Unexpected incremental update files.' }
    $paths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $manifest.files) { $null=$paths.Add($entry.path.Replace('\','/')) }
    foreach ($entry in $manifest.removals) {
        $null = Get-ArtifactPath $payload $entry.path
        if (-not $paths.Add($entry.path.Replace('\','/'))) { throw 'Duplicate removal/update path.' }
    }
    [pscustomobject]@{
        Directory=$directoryPath; Payload=$payload; Manifest=$manifest; Build=$manifest.build
        Record=[pscustomobject]@{ manifestSha256=Get-ArtifactHash $manifestPath }
    }
}

Export-ModuleMember -Function Get-TrialInputGroups, Get-TrialInputSnapshot, Get-TrialBuildPlan, Get-TrialInputChanges, Read-TrialUpdate
