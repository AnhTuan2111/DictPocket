<#
    Train the word translation probability table (lex.bin) from a bilingual corpus.

        .\scripts\train-lexicon.ps1                          download the corpus if missing, then train
        .\scripts\train-lexicon.ps1 -MaxSentences 400000     faster run for trying things out

    Run once at build time. The corpus (102 MB) does NOT ship with the app: the only product is
    the ~2 MB lex.bin in data\build.

    Corpus: OpenSubtitles en-vi from OPUS (opus.nlpl.eu), 3,505,276 sentence pairs. The QED corpus
    was tried and dropped: its Vietnamese side is machine translated and poor.

    The table is a statistical lookup (IBM Model 1, 1993), not a neural model: at runtime the app
    only reads a file and does a binary search.
#>
param(
    [int]$MaxSentences = 1200000,
    [string]$CorpusDir = "corpus",
    [switch]$KeepCorpus
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$dict    = Join-Path $root "anhviet109K.txt"
$outDir  = Join-Path $root "data\build"
$corpus  = Join-Path $root $CorpusDir
$zipPath = Join-Path $corpus "opensubtitles-en-vi.zip"
$enFile  = Join-Path $corpus "OpenSubtitles.en-vi.en"
$viFile  = Join-Path $corpus "OpenSubtitles.en-vi.vi"
$url     = "https://object.pouta.csc.fi/OPUS-OpenSubtitles/v2018/moses/en-vi.txt.zip"

if (-not (Test-Path $dict)) { throw "Missing $dict" }
New-Item -ItemType Directory -Force -Path $corpus, $outDir | Out-Null

if (-not (Test-Path $enFile)) {
    if (-not (Test-Path $zipPath)) {
        Write-Host "==> Downloading the bilingual corpus (102 MB, once)..." -ForegroundColor Cyan
        Invoke-WebRequest -Uri $url -OutFile $zipPath
    }
    Write-Host "==> Extracting..." -ForegroundColor Cyan
    Expand-Archive -Path $zipPath -DestinationPath $corpus -Force
}

Write-Host "==> Compiling..." -ForegroundColor Cyan
& mvn -q -DskipTests install
if ($LASTEXITCODE -ne 0) { throw "mvn install failed" }

Write-Host "==> Training ($('{0:N0}' -f $MaxSentences) sentence pairs)..." -ForegroundColor Cyan
& java -Xmx4g "-Dstdout.encoding=UTF-8" `
    -cp "dict-core\target\classes;dict-importer\target\classes" `
    com.anhtuan.dict.importer.cli.ImporterMain lexicon $dict $enFile $viFile $outDir $MaxSentences
if ($LASTEXITCODE -ne 0) { throw "training failed" }

if (-not $KeepCorpus) {
    Write-Host "==> Removing the corpus (use -KeepCorpus to keep it for retraining)" -ForegroundColor DarkGray
    Remove-Item $corpus -Recurse -Force
}

Write-Host "==> Done. Check the table with:" -ForegroundColor Green
Write-Host "    java -cp `"dict-core\target\classes;dict-importer\target\classes`" com.anhtuan.dict.importer.cli.ImporterMain verify data\build" -ForegroundColor Green
