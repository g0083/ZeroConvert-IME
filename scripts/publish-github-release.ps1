param(
    [string]$TagName = "v0.2.0",
    [string]$ReleaseName = "ZeroConvert-IME v0.2.0 - ZeroCon Nano v4 with High-Accuracy Distillation & Shared Engine",
    [string]$NotesPath = "dist/v0.2.0/RELEASE_NOTES.md",
    [string[]]$AssetPaths = @()
)

$ErrorActionPreference = "Stop"

# Get GitHub token from git credential helper
$credInput = "protocol=https`nhost=github.com"
$credOutput = $credInput | git credential fill
$token = $null
foreach ($line in ($credOutput -split "`r?`n")) {
    if ($line -like "password=*") {
        $token = $line.Substring(9)
        break
    }
}

if (-not $token) {
    throw "GitHub token could not be retrieved from git credential helper."
}

$headers = @{
    "Authorization" = "Bearer $token"
    "Accept"        = "application/vnd.github+json"
    "User-Agent"    = "ZeroConvert-IME-Release-Script"
}

$bodyContent = if (Test-Path -LiteralPath $NotesPath) {
    [System.IO.File]::ReadAllText((Resolve-Path $NotesPath).Path, [System.Text.Encoding]::UTF8)
} else {
    "Release $TagName"
}

$repo = "g0083/ZeroConvert-IME"

Write-Host "Checking if release '$TagName' already exists..."
$existingRelease = $null
try {
    $existingRelease = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$TagName" -Headers $headers -Method Get
} catch {
    # Release does not exist yet (404 expected)
}

$release = $null
if ($existingRelease) {
    Write-Host "Found existing release id: $($existingRelease.id)."
    $release = $existingRelease
} else {
    Write-Host "Creating new release for tag '$TagName'..."
    $createObj = @{
        tag_name   = $TagName
        name       = $ReleaseName
        body       = $bodyContent
        draft      = $false
        prerelease = $false
    }
    $createJson = $createObj | ConvertTo-Json -Depth 5
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($createJson)
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases" -Headers $headers -Method Post -Body $bytes -ContentType "application/json; charset=utf-8"
}

Write-Host "Release URL: $($release.html_url)"

# Upload assets using curl.exe for maximum reliability with large binary files
$releaseId = $release.id

foreach ($assetPath in $AssetPaths) {
    if (-not (Test-Path -LiteralPath $assetPath)) {
        Write-Warning "Asset not found: $assetPath"
        continue
    }
    
    $fullPath = (Resolve-Path $assetPath).Path
    $assetName = [System.IO.Path]::GetFileName($fullPath)
    $fileSize = (Get-Item $fullPath).Length
    Write-Host "`nProcessing asset: $assetName ($fileSize bytes)..."
    
    # Check if asset already exists in release
    $existingAssets = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/$releaseId/assets" -Headers $headers -Method Get
    $existingAsset = $existingAssets | Where-Object { $_.name -eq $assetName }
    if ($existingAsset) {
        Write-Host "Asset $assetName already exists (id: $($existingAsset.id)). Deleting first..."
        Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/assets/$($existingAsset.id)" -Headers $headers -Method Delete
    }
    
    $uploadUri = "https://uploads.github.com/repos/$repo/releases/$releaseId/assets?name=$assetName"
    Write-Host "Uploading with curl to $uploadUri..."
    
    $curlOutput = & curl.exe -s -S -X POST `
        -H "Authorization: Bearer $token" `
        -H "Accept: application/vnd.github+json" `
        -H "Content-Type: application/octet-stream" `
        --data-binary "@$fullPath" `
        "$uploadUri"
        
    $resp = $curlOutput | ConvertFrom-Json
    if ($resp.id) {
        Write-Host "Uploaded $assetName successfully (id: $($resp.id), size: $($resp.size) bytes)."
    } else {
        Write-Error "Failed to upload ${assetName}: $curlOutput"
    }
}

Write-Host "`nAll assets successfully published to $($release.html_url)!"
