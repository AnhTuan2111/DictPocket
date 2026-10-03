<#
    Package the app: a self-contained folder with DictPocket.exe, or a Windows installer. No Java needed on the target.

        .\scripts\package.ps1                         DictPocket\DictPocket.exe in dist\app-image
        .\scripts\package.ps1 -Zip                    also make a .zip for sharing
        .\scripts\package.ps1 -Type msi               installer (needs WiX 3.x)
        .\scripts\package.ps1 -Type msi -WithNmt      installer with the local AI model

    --type app-image  a folder with .exe and JRE, runs without installing.
    --type msi        a real installer; jpackage needs WiX Toolset 3.x on PATH or in tools\wix.
#>
param(
    [ValidateSet("app-image", "msi", "exe")][string]$Type = "app-image",
    [switch]$Zip,
    # Phiên bản ghi vào bộ cài và hiện trong app. Bỏ trống thì lấy từ pom (bỏ đuôi -SNAPSHOT).
    [string]$Version,
    # Kèm mô hình dịch máy nơ-ron (~98 MB mô hình + ~15 MB ONNX Runtime). Không bật thì bản đóng gói
    # không có AI và ô "dùng mô hình AI" không hiện.
    [switch]$WithNmt
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

# Hai bản phát hành dựng từ cùng một mã nguồn, chỉ khác nhau ở chỗ có mang mô hình AI hay không.
# Tên khác nhau để cài song song được và để người dùng nhìn là biết mình đang dùng bản nào.
$edition = if ($WithNmt) { "ai" } else { "standard" }
$appName = if ($WithNmt) { "DictPocket-AI" } else { "DictPocket" }

# Mã nâng cấp MSI cố định cho từng bản. Nhờ đó bản cài sau tự thay thế bản trước thay vì cài song song.
# Không được đổi hai giá trị này sau khi đã phát hành.
$upgradeUuid = if ($WithNmt) { "02ece993-c083-4b64-9e29-23ec137c62ea" } else { "283874a4-19a8-491a-83da-d248cce9d16d" }

if (-not $Version) {
    $pom = [xml](Get-Content (Join-Path $root "pom.xml"))
    $Version = ($pom.project.version -replace '-SNAPSHOT$', '')
}
Write-Host "==> Edition '$edition', version $Version" -ForegroundColor Cyan
$dist    = Join-Path $root "dist"
$stage   = Join-Path $dist "input"
$dataDir = Join-Path $root "data\build"

if (-not (Test-Path (Join-Path $dataDir "dict.pack"))) {
    throw "No data yet. Run first:  .\scripts\run.ps1 -Rebuild"
}

Write-Host "==> Compiling and packaging jars..." -ForegroundColor Cyan
# Bắt buộc có `clean`: tên jar mang số phiên bản, đổi phiên bản mà không clean thì target\ còn cả jar cũ
# lẫn jar mới, và wildcard bên dưới có thể bốc nhầm jar cũ. Bản v1.0.0 từng bị đúng lỗi này.
& mvn -q -DskipTests clean package "-Ddict.edition=$edition"
if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage, "$stage\javafx", "$stage\data" | Out-Null

# Chép đúng một jar. Nếu vì lý do nào đó target\ có hai jar thì dừng hẳn chứ không âm thầm chép nhầm.
function Copy-OneJar($pattern, $dest) {
    $found = @(Get-ChildItem $pattern -ErrorAction SilentlyContinue)
    if ($found.Count -ne 1) {
        throw "Expected exactly 1 jar matching '$pattern' but found $($found.Count): $($found.Name -join ', '). Run 'mvn clean' and retry."
    }
    Copy-Item $found[0].FullName $dest
}

Copy-OneJar "app-desktop\target\app-desktop-*.jar" "$stage\app-desktop.jar"
Copy-OneJar "dict-core\target\dict-core-*.jar"     "$stage\dict-core.jar"
Copy-Item "$dataDir\*" "$stage\data\" -Recurse -Exclude "nmt-en-vi"

# Mô hình AI chỉ kèm khi được yêu cầu, vì nó nặng gần gấp đôi phần còn lại
$nmtSrc = Join-Path $dataDir "nmt-en-vi"
if ($WithNmt) {
    if (-not (Test-Path $nmtSrc)) { throw "No model yet. Run: .\scripts\download-nmt-model.ps1" }
    Write-Host "==> Bundling the AI model (98 MB)..." -ForegroundColor Yellow
    Copy-Item $nmtSrc "$stage\data\nmt-en-vi" -Recurse
    Copy-OneJar "nmt-engine\target\nmt-engine-*.jar" "$stage\nmt-engine.jar"
    $onnxDir = Join-Path $env:USERPROFILE ".m2\repository\com\microsoft\onnxruntime\onnxruntime"
    $onnxJar = Get-ChildItem $onnxDir -Recurse -Filter "onnxruntime-*.jar" | Select-Object -First 1
    if (-not $onnxJar) { throw "ONNX Runtime jar not found in the Maven repository" }

    # Jar gốc 132 MB vì kèm thư viện native của cả bốn nền tảng. Bản cài Windows chỉ cần win-x64,
    # bỏ phần còn lại tiết kiệm ~110 MB.
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
    Write-Host "==> Without the AI model (use -WithNmt to include it)" -ForegroundColor DarkGray
}

# jpackage ném mọi jar trong thư mục đầu vào vào classpath, nên không thể vừa để JavaFX ở đó vừa khai
# báo module path (trùng module thì app chết im lặng). Bản đóng gói chạy JavaFX ở chế độ classpath
# với điểm vào là Launcher.
$m2 = Join-Path $env:USERPROFILE ".m2\repository\org\openjfx"
$fx = "25.0.4"
foreach ($mod in @("javafx-base", "javafx-graphics", "javafx-controls")) {
    foreach ($suffix in @("", "-win")) {
        $jar = "$m2\$mod\$fx\$mod-$fx$suffix.jar"
        if (-not (Test-Path $jar)) { throw "Missing $jar - run 'mvn -pl app-desktop compile' first." }
        Copy-Item $jar "$stage\javafx\"
    }
}

$out = Join-Path $dist $Type
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

# jpackage cần candle.exe / light.exe (WiX 3.x) trên PATH để làm .msi / .exe
if ($Type -ne "app-image") {
    if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
        $portable = Join-Path $root "tools\wix"
        if (Test-Path (Join-Path $portable "candle.exe")) {
            $env:PATH = "$portable;$env:PATH"
            Write-Host "==> Using portable WiX at $portable" -ForegroundColor DarkGray
        } else {
            throw "Type '$Type' needs WiX Toolset 3.x (candle.exe, light.exe). Download https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip and extract it to $portable, or use -Type app-image."
        }
    }
}

Write-Host "==> jpackage --type $Type ..." -ForegroundColor Cyan
$jpArgs = @(
    "--type", $Type,
    "--name", $appName,
    "--app-version", $Version,
    "--vendor", "AnhTuan2111",
    "--description", "Offline English-Vietnamese dictionary and translator",
    "--input", $stage,
    "--dest", $out,
    "--main-jar", "app-desktop.jar",
    "--main-class", "com.anhtuan.dict.desktop.Launcher",
    "--java-options", "--enable-native-access=ALL-UNNAMED",
    "--java-options", "-Xmx512m",
    "--add-modules", "java.base,java.desktop,java.logging,java.management,jdk.unsupported"
)

# Icon của file .exe và bộ cài; hai bản khác nhau (bản AI có tia sáng). Sinh lại bằng scripts\generate-icons.py
$icoName = if ($WithNmt) { "app-ai.ico" } else { "app.ico" }
$ico = Join-Path $root "app-desktop\src\main\resources\icon\$icoName"
if (Test-Path $ico) { $jpArgs += @("--icon", $ico) }
else { Write-Host "   ($icoName not found, using the default Java icon)" -ForegroundColor Yellow }

# --win-per-user-install: cài vào %LOCALAPPDATA% của riêng người dùng, không cần quyền administrator.
# Mặc định của jpackage là cài cho cả máy, và trên máy không có quyền admin thì bộ cài chết với
# "Error 1925". Người dùng của app này phần lớn là sinh viên dùng máy trường hoặc công ty.
# --win-menu-group: không khai báo thì shortcut vào thư mục Start Menu tên "Unknown".
if ($Type -ne "app-image") {
    $jpArgs += @("--win-dir-chooser", "--win-menu", "--win-menu-group", "DictPocket",
                 "--win-shortcut", "--win-per-user-install", "--win-upgrade-uuid", $upgradeUuid)
}

& jpackage @jpArgs
if ($LASTEXITCODE -ne 0) {
    if ($Type -ne "app-image") {
        throw "jpackage failed. Type $Type needs WiX Toolset 3.x; use -Type app-image if it is not installed."
    }
    throw "jpackage failed."
}

$size = (Get-ChildItem $out -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
Write-Host ("==> Done: {0}  ({1:N1} MB)" -f $out, $size) -ForegroundColor Green
$exe = Get-ChildItem $out -Recurse -Filter "$appName.exe" | Select-Object -First 1
if ($exe) { Write-Host "    Run: $($exe.FullName)" -ForegroundColor Green }

if ($Zip -and $Type -eq "app-image") {
    $zipPath = Join-Path $dist "$appName-$Version-windows.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    Compress-Archive -Path (Join-Path $out $appName) -DestinationPath $zipPath
    Write-Host ("==> Zipped: {0}  ({1:N1} MB)" -f $zipPath, ((Get-Item $zipPath).Length / 1MB)) -ForegroundColor Green
}
