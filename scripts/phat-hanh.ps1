<#
    Dong goi mot ban phat hanh: dat so phien ban, dung CA HAI ban, tinh ma bam, gan the git.

        .\scripts\phat-hanh.ps1 -Version 0.1.0
        .\scripts\phat-hanh.ps1 -Version 0.1.0 -NoTag     chua gan the git
        .\scripts\phat-hanh.ps1 -Version 0.2.0 -OnlyBasic chi lam ban thuong

    Ket qua nam trong dist\release\v<phien-ban>\ :
        TuDienOffline-<ver>.msi          ban thuong, khong co AI
        TuDienOffline-AI-<ver>.msi       ban kem mo hinh no-ron
        TuDienOffline-<ver>-windows.zip  ban portable, giai nen la chay, khong can cai
        SHA256SUMS.txt                   ma bam de nguoi tai kiem tra
        GHI-CHU-PHAT-HANH.md             ghi chu lay tu CHANGELOG

    CHAY SAU KHI DA TEST XONG. Script tu kiem tra: cay lam viec phai sach, toan bo test phai
    xanh, va bo nghiem thu du lieu phai dat - khong dat thi dung han, khong phat hanh.
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

if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "Phien ban phai dang X.Y.Z, vi du 0.1.0" }
$tag = "v$Version"
$releaseDir = Join-Path $root "dist\release\$tag"

# --- 1. Phai dung tren nhanh main ---
# Quy uoc: main chi chua thu da phat hanh, develop la cho lam viec hang ngay. Script gan the
# vao DUNG CHO HEAD dang dung, nen chay nham nhanh thi the nam tren develop va main thi van
# o ban cu. Da vap dung vay khi phat hanh v1.0.1.
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne "main" -and -not $AllowAnyBranch) {
    throw "Dang o nhanh '$branch'. Phat hanh tu main:`n  git switch main`n  git merge --ff-only $branch`nHoac them -AllowAnyBranch neu co y lam vay."
}

# --- 2. Cay lam viec phai sach ---
$dirty = git status --porcelain
if ($dirty) {
    throw "Con thay doi chua commit. Phat hanh tu cay lam viec ban la khong lan ra duoc ban nao da di vao bo cai:`n$dirty"
}

# --- 3. Test ---
if (-not $SkipTests) {
    Write-Host "==> Chay toan bo test..." -ForegroundColor Cyan
    & mvn -q test
    if ($LASTEXITCODE -ne 0) { throw "Test do. Khong phat hanh." }

    $dataDir = Join-Path $root "data\build"
    if (Test-Path (Join-Path $dataDir "dict.pack")) {
        Write-Host "==> Chay bo nghiem thu du lieu..." -ForegroundColor Cyan
        & java "-Dstdout.encoding=UTF-8" -cp "dict-core\target\classes;dict-importer\target\classes" `
            com.anhtuan.dict.importer.cli.ImporterMain verify $dataDir
        if ($LASTEXITCODE -ne 0) { throw "Nghiem thu du lieu khong dat. Khong phat hanh." }
    } else {
        Write-Host "   (bo qua nghiem thu: chua sinh du lieu)" -ForegroundColor DarkGray
    }
}

# --- 4. Dat so phien ban vao pom ---
Write-Host "==> Dat phien ban $Version vao pom..." -ForegroundColor Cyan
& mvn -q versions:set "-DnewVersion=$Version" -DgenerateBackupPoms=false
if ($LASTEXITCODE -ne 0) { throw "khong dat duoc phien ban" }

try {
    # --- 5. Dung hai ban ---
    if (Test-Path $releaseDir) { Remove-Item $releaseDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null

    Write-Host "==> Dung ban THUONG..." -ForegroundColor Cyan
    & "$PSScriptRoot\dong-goi.ps1" -Type msi -Version $Version
    if ($LASTEXITCODE -ne 0) { throw "dung ban thuong that bai" }
    Copy-Item "dist\msi\*.msi" $releaseDir

    if (-not $OnlyBasic) {
        Write-Host "==> Dung ban AI..." -ForegroundColor Cyan
        & "$PSScriptRoot\dong-goi.ps1" -Type msi -Version $Version -WithNmt
        if ($LASTEXITCODE -ne 0) { throw "dung ban AI that bai" }
        Copy-Item "dist\msi\*.msi" $releaseDir
    }

    # Ban portable: giai nen la chay, khong dung toi registry, xoa thu muc la sach. Co nguoi
    # khong cai duoc phan mem tren may minh, va co nguoi chi muon thu roi xoa.
    Write-Host "==> Dung ban portable (.zip)..." -ForegroundColor Cyan
    & "$PSScriptRoot\dong-goi.ps1" -Type app-image -Version $Version -Zip
    if ($LASTEXITCODE -ne 0) { throw "dung ban portable that bai" }
    Copy-Item "dist\TuDienOffline-$Version-windows.zip" $releaseDir

    # --- 6. Ma bam ---
    Write-Host "==> Tinh SHA256..." -ForegroundColor Cyan
    $lines = Get-ChildItem "$releaseDir\*.msi", "$releaseDir\*.zip" | ForEach-Object {
        $h = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
        "{0}  {1}  ({2:N1} MB)" -f $h, $_.Name, ($_.Length / 1MB)
    }
    $lines | Set-Content (Join-Path $releaseDir "SHA256SUMS.txt") -Encoding UTF8
    $lines | ForEach-Object { Write-Host "    $_" }

    # --- 7. Ghi chu phat hanh, cat tu CHANGELOG ---
    $changelog = Join-Path $root "CHANGELOG.md"
    if (Test-Path $changelog) {
        $all = Get-Content $changelog -Raw -Encoding UTF8
        $match = [regex]::Match($all, "(?ms)^## \[$([regex]::Escape($Version))\].*?(?=^## \[|\z)")
        if ($match.Success) {
            $match.Value.Trim() | Set-Content (Join-Path $releaseDir "GHI-CHU-PHAT-HANH.md") -Encoding UTF8
        } else {
            Write-Host "   (CHANGELOG chua co muc [$Version])" -ForegroundColor Yellow
        }
    }

    # --- 8. Commit + gan the ---
    if (git status --porcelain) {
        git add -A
        git commit -q -m "chore: phat hanh $tag"
    }
    if (-not $NoTag) {
        git tag -a $tag -m "$tag"
        Write-Host "==> Da gan the $tag (chua day len remote)" -ForegroundColor Green
        Write-Host "    Day len bang:  git push origin main --follow-tags" -ForegroundColor Green
    }
} finally {
    # Quay ve -SNAPSHOT de lan sau lam tiep khong dam vao phien ban da phat hanh
    if (-not $NoTag) {
        $next = $Version -replace '(\d+)$', { [int]$args[0].Value + 1 }
        & mvn -q versions:set "-DnewVersion=$next-SNAPSHOT" -DgenerateBackupPoms=false | Out-Null
        if (git status --porcelain) {
            git add -A
            git commit -q -m "chore: mo vong phat trien $next-SNAPSHOT"
        }
    }
}

Write-Host ""
Write-Host "==> Xong: $releaseDir" -ForegroundColor Green
Get-ChildItem $releaseDir | ForEach-Object { "    {0,-40} {1,8:N1} MB" -f $_.Name, ($_.Length/1MB) }
