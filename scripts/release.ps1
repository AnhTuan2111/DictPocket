<#
    Build a release: set the version, build BOTH editions, compute hashes, tag git.

        .\scripts\release.ps1 -Version 1.1.0
        .\scripts\release.ps1 -Version 1.1.0 -NoTag        do not tag
        .\scripts\release.ps1 -Version 1.1.0 -OnlyBasic    standard edition only

    Output in dist\release\v<version>\ . File names carry no version, so the download links in the
    README (releases/latest/download/<name>) never break when a new version comes out:
        DictPocket-Setup.msi           standard edition (no AI)
        DictPocket-AI-Setup.msi        edition with the local neural model
        DictPocket-Portable.zip        portable: unzip and run, no install
        SHA256SUMS.txt                 hashes for download verification
        RELEASE-NOTES.md               taken from CHANGELOG.md, used as the release description

    Run after testing. The script checks that the working tree is clean, all tests pass and the
    data acceptance suite passes; otherwise it stops.
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [switch]$NoTag,
    [switch]$OnlyBasic,
    [switch]$SkipTests,
    [switch]$AllowAnyBranch
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "Version must look like X.Y.Z, for example 1.1.0" }
$tag = "v$Version"
$releaseDir = Join-Path $root "dist\release\$tag"

# main chỉ chứa thứ đã phát hành, develop là chỗ làm việc hằng ngày. Script gắn tag vào đúng HEAD đang đứng,
# chạy nhầm nhánh thì tag nằm trên develop còn main vẫn ở bản cũ.
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne "main" -and -not $AllowAnyBranch) {
    throw "On branch '$branch'. Release from main:`n  git switch main`n  git merge --ff-only $branch`nOr pass -AllowAnyBranch."
}

# Phát hành từ cây làm việc bẩn thì không biết thay đổi nào đã vào bộ cài
$dirty = git status --porcelain
if ($dirty) {
    throw "Uncommitted changes found. Releasing from a dirty tree hides what went into the installer:`n$dirty"
}

if (-not $SkipTests) {
    Write-Host "==> Running all tests..." -ForegroundColor Cyan
    & mvn -q test
    if ($LASTEXITCODE -ne 0) { throw "Tests failed. Not releasing." }

    $dataDir = Join-Path $root "data\build"
    if (Test-Path (Join-Path $dataDir "dict.pack")) {
        Write-Host "==> Running the data acceptance suite..." -ForegroundColor Cyan
        & java "-Dstdout.encoding=UTF-8" -cp "dict-core\target\classes;dict-importer\target\classes" `
            com.anhtuan.dict.importer.cli.ImporterMain verify $dataDir
        if ($LASTEXITCODE -ne 0) { throw "Data acceptance failed. Not releasing." }
    } else {
        Write-Host "   (skipping acceptance: no data built)" -ForegroundColor DarkGray
    }
}

Write-Host "==> Setting version $Version in the poms..." -ForegroundColor Cyan
& mvn -q versions:set "-DnewVersion=$Version" -DgenerateBackupPoms=false
if ($LASTEXITCODE -ne 0) { throw "could not set the version" }

try {
    if (Test-Path $releaseDir) { Remove-Item $releaseDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null

    Write-Host "==> Building the standard edition..." -ForegroundColor Cyan
    & "$PSScriptRoot\package.ps1" -Type msi -Version $Version
    if ($LASTEXITCODE -ne 0) { throw "standard edition build failed" }
    Copy-Item "dist\msi\DictPocket-$Version.msi" (Join-Path $releaseDir "DictPocket-Setup.msi")

    if (-not $OnlyBasic) {
        Write-Host "==> Building the AI edition..." -ForegroundColor Cyan
        & "$PSScriptRoot\package.ps1" -Type msi -Version $Version -WithNmt
        if ($LASTEXITCODE -ne 0) { throw "AI edition build failed" }
        Copy-Item "dist\msi\DictPocket-AI-$Version.msi" (Join-Path $releaseDir "DictPocket-AI-Setup.msi")
    }

    # Bản portable: giải nén là chạy, không đụng registry, xoá thư mục là sạch. Có người không cài được
    # phần mềm trên máy mình, và có người chỉ muốn thử rồi xoá.
    Write-Host "==> Building the portable zip..." -ForegroundColor Cyan
    & "$PSScriptRoot\package.ps1" -Type app-image -Version $Version -Zip
    if ($LASTEXITCODE -ne 0) { throw "portable build failed" }
    Copy-Item "dist\DictPocket-$Version-windows.zip" (Join-Path $releaseDir "DictPocket-Portable.zip")

    Write-Host "==> Computing SHA256..." -ForegroundColor Cyan
    $lines = Get-ChildItem "$releaseDir\*.msi", "$releaseDir\*.zip" | ForEach-Object {
        $h = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
        "{0}  {1}  ({2:N1} MB)" -f $h, $_.Name, ($_.Length / 1MB)
    }
    $lines | Set-Content (Join-Path $releaseDir "SHA256SUMS.txt") -Encoding UTF8
    $lines | ForEach-Object { Write-Host "    $_" }

    # Ghi chú phát hành cắt từ mục tương ứng trong CHANGELOG
    $changelog = Join-Path $root "CHANGELOG.md"
    if (Test-Path $changelog) {
        $all = Get-Content $changelog -Raw -Encoding UTF8
        $match = [regex]::Match($all, "(?ms)^## \[$([regex]::Escape($Version))\].*?(?=^## \[|\z)")
        if ($match.Success) {
            # Người dùng cuối chỉ cần phần dành cho họ: bỏ dòng tiêu đề và mục "Cho lập trình viên"
            $notes = ($match.Value.Trim() -split "`n", 2)[1].Trim()
            $dev = $notes.IndexOf("### Cho lập trình viên")
            if ($dev -gt 0) { $notes = $notes.Substring(0, $dev).Trim() }
            $notes | Set-Content (Join-Path $releaseDir "RELEASE-NOTES.md") -Encoding UTF8
        } else {
            Write-Host "   (CHANGELOG has no [$Version] section)" -ForegroundColor Yellow
        }
    }

    if (git status --porcelain) {
        git add -A
        git commit -q -m "chore: phát hành $tag"
    }
    if (-not $NoTag) {
        git tag -a $tag -m "$tag"
        Write-Host "==> Tagged $tag (not pushed yet)" -ForegroundColor Green
        Write-Host "    Push with:  git push origin main --follow-tags" -ForegroundColor Green
    }
} finally {
    # Quay về -SNAPSHOT để lần làm tiếp theo không đụng vào phiên bản đã phát hành
    if (-not $NoTag) {
        $next = $Version -replace '(\d+)$', { [int]$args[0].Value + 1 }
        & mvn -q versions:set "-DnewVersion=$next-SNAPSHOT" -DgenerateBackupPoms=false | Out-Null
        if (git status --porcelain) {
            git add -A
            git commit -q -m "chore: mở vòng phát triển $next-SNAPSHOT"
        }
    }
}

Write-Host ""
Write-Host "==> Done: $releaseDir" -ForegroundColor Green
Get-ChildItem $releaseDir | ForEach-Object { "    {0,-40} {1,8:N1} MB" -f $_.Name, ($_.Length/1MB) }
