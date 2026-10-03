<#
    Run the app from source.

        .\scripts\run.ps1                          open the app
        .\scripts\run.ps1 -Query "give up"         open with a query
        .\scripts\run.ps1 -Query "cham soc" -Mode reverse
        .\scripts\run.ps1 -Rebuild                 rebuild dict.pack and the indexes first
        .\scripts\run.ps1 -Query "give up" -Screenshot docs\screenshots\word.png

    Compiles if needed, builds the data if missing, then starts the app.
    Does not use `mvn javafx:run`: it is slower and swallows stdout.
#>
param(
    [string]$Query,
    [ValidateSet("word", "sentence", "reverse")][string]$Mode = "word",
    [switch]$Rebuild,
    # Tick "use AI model" on start. Needs scripts\download-nmt-model.ps1 to have run.
    [switch]$Nmt,
    # Run as the standard edition: leave the AI engine off the classpath so the AI checkbox does not appear
    [switch]$NoNmt,
    # Save a PNG of the window to this path and exit (used for documentation images)
    [string]$Screenshot
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$source  = Join-Path $root "anhviet109K.txt"
$dataDir = Join-Path $root "data\build"
$pack    = Join-Path $dataDir "dict.pack"

# Bỏ qua test cho nhanh, test chạy riêng bằng `mvn test`
Write-Host "==> Compiling..." -ForegroundColor Cyan
& mvn -q -DskipTests install
if ($LASTEXITCODE -ne 0) { throw "mvn install failed" }

if ($Rebuild -or -not (Test-Path $pack)) {
    if (-not (Test-Path $source)) { throw "Missing $source" }
    # Nguồn phụ trong data\*.tsv (thuật ngữ CNTT, y tế...), xếp theo tên file: nguồn đứng trước được ưu tiên hơn
    $extras = @(Get-ChildItem (Join-Path $root "data") -Filter *.tsv | Sort-Object Name | ForEach-Object { $_.FullName })
    Write-Host "==> Building dict.pack and indexes ($($extras.Count) extra sources)..." -ForegroundColor Cyan
    & java -Xmx3g "-Dstdout.encoding=UTF-8" `
        -cp "dict-core\target\classes;dict-importer\target\classes" `
        com.anhtuan.dict.importer.cli.ImporterMain build $source $dataDir @extras
    if ($LASTEXITCODE -ne 0) { throw "data build failed" }
}

# Classpath đầy đủ (gồm nmt-engine nếu đã build) để Maven tự tính, bỏ JavaFX vì chạy bằng module path
& mvn -q -pl app-desktop dependency:build-classpath "-Dmdep.outputFile=target/cp.txt" | Out-Null
$cp = "app-desktop\target\classes;dict-core\target\classes"
$cpFile = "app-desktop\target\cp.txt"
if (Test-Path $cpFile) {
    $skip = 'junit|opentest4j|apiguardian|jspecify|javafx'
    if ($NoNmt) { $skip += '|nmt-engine|onnxruntime' }
    $extra = (Get-Content $cpFile -Raw).Trim() -split ';' | Where-Object { $_ -notmatch $skip }
    if ($extra) { $cp = $cp + ';' + ($extra -join ';') }
}

# Module path của JavaFX lấy thẳng từ kho Maven cục bộ
$m2 = Join-Path $env:USERPROFILE ".m2\repository\org\openjfx"
$fx = "25.0.4"
$jars = @("javafx-base", "javafx-graphics", "javafx-controls") | ForEach-Object {
    @("$m2\$_\$fx\$_-$fx.jar", "$m2\$_\$fx\$_-$fx-win.jar")
}
$missing = $jars | Where-Object { -not (Test-Path $_) }
if ($missing) { throw "Missing JavaFX jars: $missing`nRun 'mvn -pl app-desktop compile' first." }

$jvm = @(
    "--module-path", ($jars -join ";"),
    "--add-modules", "javafx.controls",
    "--enable-native-access=javafx.graphics",
    "-cp", $cp
)
if ($Query) { $jvm += "-Ddict.query=$Query"; $jvm += "-Ddict.mode=$Mode" }
if ($Nmt) { $jvm += "-Ddict.nmt=true"; $jvm += "--enable-native-access=ALL-UNNAMED" }
if ($Screenshot) {
    New-Item -ItemType Directory -Force -Path (Split-Path (Join-Path $root $Screenshot) -Parent) | Out-Null
    $jvm += "-Ddict.screenshot=$(Join-Path $root $Screenshot)"
}
$jvm += "com.anhtuan.dict.desktop.DictApp"

Write-Host "==> Starting the app..." -ForegroundColor Cyan
& java @jvm
