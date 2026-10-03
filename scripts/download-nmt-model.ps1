<#
    Download the local neural translation model (~98 MB).

        .\scripts\download-nmt-model.ps1           download into data\build\nmt-en-vi
        .\scripts\download-nmt-model.ps1 -Remove   remove it

    This is an AI model running on the user's machine. Everything else in the app (dictionary,
    probability table, grammar rules) has no model. Once downloaded it runs fully offline.

    Model: Helsinki-NLP/opus-mt-en-vi (Apache-2.0), exported to ONNX and 8-bit quantized by Xenova.
    Source: https://huggingface.co/Xenova/opus-mt-en-vi

    The app runs fine without the model: the "use AI model" checkbox simply does not appear.
#>
param(
    [string]$DataDir = "data\build",
    [switch]$Remove
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$modelDir = Join-Path $root (Join-Path $DataDir "nmt-en-vi")

if ($Remove) {
    if (Test-Path $modelDir) { Remove-Item $modelDir -Recurse -Force; Write-Host "Model removed." }
    else { Write-Host "Nothing to remove." }
    return
}

$base = "https://huggingface.co/Xenova/opus-mt-en-vi/resolve/main"
New-Item -ItemType Directory -Force -Path $modelDir, (Join-Path $modelDir "onnx") | Out-Null

$files = @(
    @{ Url = "$base/config.json";                          Path = "config.json" },
    @{ Url = "$base/tokenizer.json";                       Path = "tokenizer.json" },
    @{ Url = "$base/onnx/encoder_model_quantized.onnx";    Path = "onnx\encoder_model_quantized.onnx" },
    @{ Url = "$base/onnx/decoder_model_quantized.onnx";    Path = "onnx\decoder_model_quantized.onnx" }
)

foreach ($f in $files) {
    $target = Join-Path $modelDir $f.Path
    if (Test-Path $target) { Write-Host "  already have $($f.Path)" -ForegroundColor DarkGray; continue }
    Write-Host "==> Downloading $($f.Path) ..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri $f.Url -OutFile $target
}

# tokenizer.json -> vocab.tsv. Thư viện tokenizers của HuggingFace làm sập cả JVM với mô hình này,
# nên MarianTokenizer tự tách từ và chỉ cần từ vựng dạng phẳng: mỗi dòng "mảnh<TAB>điểm", số thứ tự dòng là id.
$vocabTsv = Join-Path $modelDir "vocab.tsv"
if (-not (Test-Path $vocabTsv)) {
    Write-Host "==> Converting tokenizer.json to vocab.tsv ..." -ForegroundColor Cyan
    $json = Get-Content (Join-Path $modelDir "tokenizer.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    $sb = [System.Text.StringBuilder]::new()
    foreach ($entry in $json.model.vocab) {
        [void]$sb.Append($entry[0]).Append("`t").Append($entry[1]).Append("`n")
    }
    [System.IO.File]::WriteAllText($vocabTsv, $sb.ToString(), [System.Text.UTF8Encoding]::new($false))
    Write-Host "    $($json.model.vocab.Count) vocabulary entries"
}

$size = (Get-ChildItem $modelDir -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
Write-Host ("==> Done: {0} ({1:N1} MB)" -f $modelDir, $size) -ForegroundColor Green
Write-Host "    Try it: .\scripts\run.ps1 -Nmt -Mode sentence -Query 'She went to the market yesterday'" -ForegroundColor Green
