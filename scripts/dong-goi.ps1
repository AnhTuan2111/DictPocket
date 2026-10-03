<#
    Dong goi thanh ban chay duoc: mot thu muc co san file .exe, khong can cai Java.

        .\scripts\dong-goi.ps1            tao dist\TuDienOffline\TuDienOffline.exe
        .\scripts\dong-goi.ps1 -Zip       tao them file .zip de gui cho nguoi khac

    Ghi chu ve kieu dong goi (PLAN.md AD-11, M8):
      --type app-image  -> mot THU MUC chua san .exe + JRE. Khong can cai dat gi them,
                           chay ngay. Day la thu script nay tao.
      --type msi / exe  -> bo cai dat that su. Can WiX Toolset v3.11 cai san tren may;
                           chua co thi jpackage bao loi. Cai WiX xong thi doi -Type.
#>
param(
    [ValidateSet("app-image", "msi", "exe")][string]$Type = "app-image",
    [switch]$Zip,
    # Phien ban ghi vao bo cai va hien trong app. Bo trong thi lay tu pom (bo duoi -SNAPSHOT).
    [string]$Version,
    # Kem theo mo hinh dich may no-ron (~98 MB mo hinh + ~15 MB thu vien ONNX Runtime).
    # Khong bat thi ban dong goi khong co AI, va o "dung mo hinh AI" khong hien ra.
    [switch]$WithNmt
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

# Hai BAN PHAT HANH tu cung mot ma nguon, khac nhau duy nhat o cho co mang mo hinh AI hay
# khong. Ten khac nhau de cai song song duoc va de nguoi dung nhin la biet minh dang tai gi.
$edition = if ($WithNmt) { "ai" } else { "thuong" }
$appName = if ($WithNmt) { "TuDienOffline-AI" } else { "TuDienOffline" }

if (-not $Version) {
    $pom = [xml](Get-Content (Join-Path $root "pom.xml"))
    $Version = ($pom.project.version -replace '-SNAPSHOT$', '')
}
Write-Host "==> Ban '$edition', phien ban $Version" -ForegroundColor Cyan
$dist    = Join-Path $root "dist"
$stage   = Join-Path $dist "input"
$dataDir = Join-Path $root "data\build"

if (-not (Test-Path (Join-Path $dataDir "dict.pack"))) {
    throw "Chua co du lieu. Chay truoc:  .\scripts\run.ps1 -Rebuild"
}

Write-Host "==> Bien dich va dong goi jar..." -ForegroundColor Cyan
# PHAI co `clean`. Ten file jar co mang so phien ban, nen doi phien ban roi build lai ma
# khong clean thi target\ con ca jar CU lan jar MOI. Doan Copy-Item ben duoi lay jar bang
# wildcard, va khi wildcard khop nhieu file thi file cuoi thang - tuc la jar cu.
# Ban v1.0.0 da phat hanh nham kieu nay: bo cai ghi dung 1.0.0 nhung app ben trong la jar
# 1.0.1-SNAPSHOT con sot tu lan build truoc, thanh tieu de hien "v1.0.1-SNAPSHOT".
& mvn -q -DskipTests clean package "-Ddict.edition=$edition"
if ($LASTEXITCODE -ne 0) { throw "mvn package that bai" }

# --- dung thu muc nguyen lieu ---
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage, "$stage\javafx", "$stage\data" | Out-Null

# Chep DUNG mot jar. `clean` o tren da lo cho khong con jar cu, nhung day la chot chan thu
# hai: neu vi ly do gi ma target\ lai co hai jar, thi dung han chu khong am tham chep nham
# roi dong goi ra mot ban cai sai - dung kieu loi da lam hong ban v1.0.0.
function Copy-OneJar($pattern, $dest) {
    $found = @(Get-ChildItem $pattern -ErrorAction SilentlyContinue)
    if ($found.Count -ne 1) {
        throw "Mong doi dung 1 jar khop '$pattern' nhung thay $($found.Count): $($found.Name -join ', '). Chay `mvn clean` roi lam lai."
    }
    Copy-Item $found[0].FullName $dest
}

Copy-OneJar "app-desktop\target\app-desktop-*.jar" "$stage\app-desktop.jar"
Copy-OneJar "dict-core\target\dict-core-*.jar"     "$stage\dict-core.jar"
Copy-Item "$dataDir\*" "$stage\data\" -Recurse -Exclude "nmt-en-vi"

# Mo hinh AI: chi kem khi duoc yeu cau. No nang gan gap doi ca ung dung con lai.
$nmtSrc = Join-Path $dataDir "nmt-en-vi"
if ($WithNmt) {
    if (-not (Test-Path $nmtSrc)) { throw "Chua co mo hinh. Chay: .\scripts\tai-model-nmt.ps1" }
    Write-Host "==> Kem theo mo hinh AI (98 MB)..." -ForegroundColor Yellow
    Copy-Item $nmtSrc "$stage\data\nmt-en-vi" -Recurse
    Copy-OneJar "nmt-engine\target\nmt-engine-*.jar" "$stage\nmt-engine.jar"
    $onnxDir = Join-Path $env:USERPROFILE ".m2\repository\com\microsoft\onnxruntime\onnxruntime"
    $onnxJar = Get-ChildItem $onnxDir -Recurse -Filter "onnxruntime-*.jar" | Select-Object -First 1
    if (-not $onnxJar) { throw "Khong thay jar ONNX Runtime trong kho Maven" }

    # Jar goc 132 MB vi kem thu vien native cua CA BON nen tang (linux, macOS...).
    # Ban cai cho Windows chi can win-x64; bo phan con lai tiet kiem ~110 MB.
    $unpack = Join-Path $dist "onnx-unpack"
    if (Test-Path $unpack) { Remove-Item $unpack -Recurse -Force }
    Expand-Archive -Path $onnxJar.FullName -DestinationPath $unpack
    Get-ChildItem (Join-Path $unpack "ai\onnxruntime\native") -Directory |
        Where-Object { $_.Name -ne "win-x64" } |
        ForEach-Object { Remove-Item $_.FullName -Recurse -Force }
    Compress-Archive -Path (Join-Path $unpack "*") -DestinationPath "$stage\onnxruntime.zip" -Force
    Move-Item "$stage\onnxruntime.zip" "$stage\onnxruntime.jar" -Force
    Remove-Item $unpack -Recurse -Force
} else {
    Write-Host "==> Ban KHONG kem mo hinh AI (dung -WithNmt neu muon)" -ForegroundColor DarkGray
}

# JavaFX: jpackage nem MOI jar trong thu muc dau vao (ke ca thu muc con) vao classpath,
# nen khong the vua de day vua khai bao module path - trung module thi app chet im lang.
# Vi vay ban dong goi chay JavaFX o che do CLASSPATH, voi diem vao la Launcher
# (xem javadoc cua Launcher.java). Ban chay tu ma nguon van dung module path chuan.
$m2 = Join-Path $env:USERPROFILE ".m2\repository\org\openjfx"
$fx = "25.0.4"
foreach ($mod in @("javafx-base", "javafx-graphics", "javafx-controls")) {
    foreach ($suffix in @("", "-win")) {
        $jar = "$m2\$mod\$fx\$mod-$fx$suffix.jar"
        if (-not (Test-Path $jar)) { throw "Thieu $jar - chay `mvn -pl app-desktop compile` truoc." }
        Copy-Item $jar "$stage\javafx\"
    }
}

# --- jpackage ---
$out = Join-Path $dist $Type
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

# WiX: jpackage can candle.exe/light.exe (WiX 3.x) tren PATH de lam .msi/.exe.
# Tim ban da cai, khong co thi tim ban portable trong tools\wix cua du an.
if ($Type -ne "app-image") {
    if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
        $portable = Join-Path $root "tools\wix"
        if (Test-Path (Join-Path $portable "candle.exe")) {
            $env:PATH = "$portable;$env:PATH"
            Write-Host "==> Dung WiX portable o $portable" -ForegroundColor DarkGray
        } else {
            throw "Kieu $Type can WiX Toolset 3.x (candle.exe, light.exe) ma may chua co. Tai https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip roi giai nen vao $portable, hoac cu dung -Type app-image."
        }
    }
}

Write-Host "==> jpackage --type $Type ..." -ForegroundColor Cyan
$jpArgs = @(
    "--type", $Type,
    "--name", $appName,
    "--app-version", $Version,
    "--vendor", "AnhTuan2111",
    "--description", "Tu dien va dich offline Anh - Viet",
    "--input", $stage,
    "--dest", $out,
    "--main-jar", "app-desktop.jar",
    "--main-class", "com.anhtuan.dict.desktop.Launcher",
    "--java-options", "--enable-native-access=ALL-UNNAMED",
    "--java-options", "-Xmx512m",
    "--add-modules", "java.base,java.desktop,java.logging,java.management,jdk.unsupported"
)

# Icon cua file .exe va cua bo cai. Hai ban khac mau de nhin thanh taskbar la biet dang mo
# ban nao. Sinh lai bang: python scripts\tao-icon.py
$icoName = if ($WithNmt) { "app-ai.ico" } else { "app.ico" }
$ico = Join-Path $root "app-desktop\src\main\resources\icon\$icoName"
if (Test-Path $ico) { $jpArgs += @("--icon", $ico) }
else { Write-Host "   (khong thay $icoName, dung icon Java mac dinh)" -ForegroundColor Yellow }

# --win-per-user-install: cai vao %LOCALAPPDATA% cua rieng nguoi dung, KHONG can quyen
# administrator. Mac dinh cua jpackage la cai cho ca may, va tren may khong co quyen admin
# thi bo cai chet voi "Error 1925 - You do not have sufficient privileges". Nguoi dung cua
# ung dung nay phan lon la sinh vien dung may truong hoac may cong ty, khong co quyen do.
#
# --win-menu-group: khong khai bao thi jpackage xep shortcut vao thu muc Start Menu ten la
# "Unknown". Da cai thu va thay dung nhu vay.
if ($Type -ne "app-image") {
    $jpArgs += @("--win-dir-chooser", "--win-menu", "--win-menu-group", "Tu dien offline",
                 "--win-shortcut", "--win-per-user-install")
}

& jpackage @jpArgs
if ($LASTEXITCODE -ne 0) {
    if ($Type -ne "app-image") {
        throw "jpackage that bai. Kieu $Type can WiX Toolset v3.11; chua cai thi dung -Type app-image."
    }
    throw "jpackage that bai."
}

$size = (Get-ChildItem $out -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
Write-Host ("==> Xong: {0}  ({1:N1} MB)" -f $out, $size) -ForegroundColor Green
$exe = Get-ChildItem $out -Recurse -Filter "$appName.exe" | Select-Object -First 1
if ($exe) { Write-Host "    Chay bang: $($exe.FullName)" -ForegroundColor Green }

if ($Zip -and $Type -eq "app-image") {
    $zipPath = Join-Path $dist "$appName-$Version-windows.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    Compress-Archive -Path (Join-Path $out $appName) -DestinationPath $zipPath
    $zipMb = (Get-Item $zipPath).Length / 1MB
    Write-Host ("==> Da nen: {0}  ({1:N1} MB)" -f $zipPath, $zipMb) -ForegroundColor Green
}
