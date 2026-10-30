param([string]$Root = 'K:\muz_gen', [string]$EngineRoot = 'C:\AI\muz_gen')
$ErrorActionPreference = 'Stop'
$env:PIP_CACHE_DIR = Join-Path $Root 'cache\pip'
$env:TEMP = Join-Path $Root 'tmp'
$env:TMP = $env:TEMP
foreach ($folder in @('runtime', 'cache\ace-runtime', 'logs', 'tmp', 'jobs', 'lyrics-prompts', 'lyrics', 'music-prompts')) {
    New-Item -ItemType Directory -Force (Join-Path $Root $folder) | Out-Null
}
foreach ($folder in @('runtime', 'models', 'cache\uv')) {
    New-Item -ItemType Directory -Force (Join-Path $EngineRoot $folder) | Out-Null
}
$bootstrap = Join-Path $EngineRoot 'runtime\bootstrap'
if (!(Test-Path -LiteralPath (Join-Path $bootstrap 'Scripts\python.exe'))) {
    python -m venv $bootstrap
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create the download environment' }
}
& (Join-Path $bootstrap 'Scripts\python.exe') -m pip install uv huggingface_hub
if ($LASTEXITCODE -ne 0) { throw 'Cannot install download tools' }
$runtime = Join-Path $EngineRoot 'runtime\ACE-Step-1.5'
if (!(Test-Path -LiteralPath (Join-Path $runtime 'pyproject.toml'))) {
    git clone https://github.com/ace-step/ACE-Step-1.5.git $runtime
    if ($LASTEXITCODE -ne 0) { throw 'Cannot clone ACE-Step' }
}
$env:UV_CACHE_DIR = Join-Path $EngineRoot 'cache\uv'
$env:UV_PYTHON_INSTALL_DIR = Join-Path $EngineRoot 'runtime\python'
$env:HF_HOME = Join-Path $Root 'cache\huggingface'
$env:TEMP = Join-Path $Root 'tmp'
$env:TMP = $env:TEMP
Push-Location $runtime
try {
    & (Join-Path $bootstrap 'Scripts\uv.exe') sync --python 3.12
    if ($LASTEXITCODE -ne 0) { throw 'Cannot install ACE-Step dependencies' }
} finally { Pop-Location }
$hf = Join-Path $bootstrap 'Scripts\hf.exe'
& $hf download ACE-Step/Ace-Step1.5 --local-dir (Join-Path $EngineRoot 'models')
if ($LASTEXITCODE -ne 0) { throw 'Cannot download the core models' }
& $hf download ACE-Step/acestep-v15-xl-turbo --local-dir (Join-Path $EngineRoot 'models\acestep-v15-xl-turbo')
if ($LASTEXITCODE -ne 0) { throw 'Cannot download XL' }
ollama pull qwen3:14b
if ($LASTEXITCODE -ne 0) { throw 'Cannot download the lyrics model' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-music.ps1') -Destination (Join-Path $Root 'runtime\start-music.ps1') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'music_runtime_fixes.py') -Destination (Join-Path $Root 'runtime\music_runtime_fixes.py') -Force
@{engineRoot = [System.IO.Path]::GetFullPath($EngineRoot)} | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $Root 'runtime\engine-location.json')
foreach ($link in @(@('checkpoints', (Join-Path $EngineRoot 'models')), @('.cache', (Join-Path $Root 'cache\ace-runtime')))) {
    $linkPath = Join-Path $runtime $link[0]
    if (!(Test-Path -LiteralPath $linkPath)) {
        New-Item -ItemType Junction -Path $linkPath -Target $link[1] | Out-Null
    }
}
Write-Host "Ready. Verify: python scripts\verify-music-models.py --root $EngineRoot\models"
