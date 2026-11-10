param([string]$Root = 'K:\muz_gen', [string]$EngineRoot = 'C:\AI\muz_gen', [switch]$IncludeClone)
$ErrorActionPreference = 'Stop'
$location = Join-Path $Root 'runtime\engine-location.json'
if (!$PSBoundParameters.ContainsKey('EngineRoot') -and (Test-Path -LiteralPath $location)) {
    $EngineRoot = (Get-Content -LiteralPath $location -Raw | ConvertFrom-Json).engineRoot
}
foreach ($folder in @('tmp', 'cache\uv', 'cache\huggingface', 'runtime', 'logs')) {
    New-Item -ItemType Directory -Force (Join-Path $Root $folder) | Out-Null
}
$env:UV_CACHE_DIR = Join-Path $Root 'cache\uv'
$env:HF_HOME = Join-Path $Root 'cache\huggingface'
$env:TEMP = Join-Path $Root 'tmp'
$env:TMP = $env:TEMP
$env:HF_HUB_OFFLINE = '0'
$env:HF_HUB_DISABLE_TELEMETRY = '1'
$uv = Join-Path $EngineRoot 'runtime\bootstrap\Scripts\uv.exe'
$basePython = Join-Path $EngineRoot 'runtime\ACE-Step-1.5\.venv\Scripts\python.exe'
$venv = Join-Path $EngineRoot 'runtime\tts\.venv'
if (!(Test-Path -LiteralPath (Join-Path $venv 'Scripts\python.exe'))) {
    & $uv venv --python $basePython $venv
    if ($LASTEXITCODE -ne 0) { throw 'Could not create speech environment' }
}
$python = Join-Path $venv 'Scripts\python.exe'
& $uv pip install --python $python 'torch==2.7.1+cu128' 'torchaudio==2.7.1+cu128' --index-url https://download.pytorch.org/whl/cu128
if ($LASTEXITCODE -ne 0) { throw 'Could not install CUDA PyTorch' }
& $uv pip install --python $python 'qwen-tts==0.1.1' 'huggingface-hub<1' 'hf-xet>=1.2' 'numpy<2.4'
if ($LASTEXITCODE -ne 0) { throw 'Could not install Qwen TTS' }
$model = Join-Path $EngineRoot 'models\Qwen3-TTS-12Hz-1.7B-VoiceDesign'
& (Join-Path $venv 'Scripts\hf.exe') download Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign --local-dir $model --max-workers 3
if ($LASTEXITCODE -ne 0) { throw 'Could not download VoiceDesign model' }
if ($IncludeClone) {
    $cloneModel = Join-Path $EngineRoot 'models\Qwen3-TTS-12Hz-1.7B-Base'
    & (Join-Path $venv 'Scripts\hf.exe') download Qwen/Qwen3-TTS-12Hz-1.7B-Base --local-dir $cloneModel --max-workers 3
    if ($LASTEXITCODE -ne 0) { throw 'Could not download voice cloning model' }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'generate_speech.py') -Destination (Join-Path $Root 'runtime\generate_speech.py') -Force
Write-Host "Local speech model ready: $model"
