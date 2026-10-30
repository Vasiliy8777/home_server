param(
    [ValidateSet('acestep-v15-xl-turbo', 'acestep-v15-turbo')]
    [string]$Model = 'acestep-v15-xl-turbo',
    [string]$Root = 'K:\muz_gen',
    [string]$EngineRoot = 'C:\AI\muz_gen',
    [switch]$Online
)
$ErrorActionPreference = 'Stop'
$locationFile = Join-Path $Root 'runtime\engine-location.json'
if (!$PSBoundParameters.ContainsKey('EngineRoot') -and (Test-Path -LiteralPath $locationFile)) {
    $EngineRoot = (Get-Content -LiteralPath $locationFile -Raw | ConvertFrom-Json).engineRoot
}
if ([string]::IsNullOrWhiteSpace($EngineRoot)) { throw 'EngineRoot is missing in engine-location.json' }
$runtime = Join-Path $EngineRoot 'runtime\ACE-Step-1.5'
$python = Join-Path $runtime '.venv\Scripts\python.exe'
if (!(Test-Path -LiteralPath $python)) { throw 'Run scripts\setup-music.ps1 first' }
foreach ($folder in @('tmp\ace-step', 'cache\huggingface', 'cache\torch', 'cache\triton', 'cache\inductor', 'cache\matplotlib', 'cache\numba', 'cache\modelscope', 'logs')) {
    New-Item -ItemType Directory -Force (Join-Path $Root $folder) | Out-Null
}
# One GPU worker. Components return to RAM between uses so Ollama can write lyrics.
$env:ACESTEP_CONFIG_PATH = $Model
$env:ACESTEP_CHECKPOINTS_DIR = Join-Path $EngineRoot 'models'
$env:ACESTEP_LM_MODEL_PATH = 'acestep-5Hz-lm-1.7B'
$env:ACESTEP_LM_BACKEND = 'pt'
$env:ACESTEP_DEVICE = 'cuda'
# RTX 4070 reports 11.994 GiB; ACE-Step assigns even exactly 12 to the lower tier.
# Select the 12-16 GB profile at its boundary so the offloaded 1.7B LM is retained.
# This does not increase physical VRAM. All CPU offload options stay enabled.
$env:MAX_CUDA_VRAM = '12.001'
$env:ACESTEP_INIT_LLM = 'true'
$env:ACESTEP_NO_INIT = 'true'
$env:ACESTEP_OFFLOAD_TO_CPU = 'true'
$env:ACESTEP_OFFLOAD_DIT_TO_CPU = 'true'
$env:ACESTEP_LM_OFFLOAD_TO_CPU = 'true'
$env:PYTORCH_CUDA_ALLOC_CONF = 'backend:cudaMallocAsync'
$env:ACESTEP_USE_FLASH_ATTENTION = 'false'
$env:ACESTEP_COMPILE_MODEL = 'false'
$env:ACESTEP_QUEUE_WORKERS = '1'
$env:ACESTEP_API_WORKERS = '1'
$env:ACESTEP_ON_DEMAND_MODEL_LOAD = 'true'
$env:ACESTEP_TMPDIR = Join-Path $Root 'tmp\ace-step'
$env:TEMP = $env:ACESTEP_TMPDIR
$env:TMP = $env:ACESTEP_TMPDIR
$env:HF_HOME = Join-Path $Root 'cache\huggingface'
$env:TORCH_HOME = Join-Path $Root 'cache\torch'
$env:TRITON_CACHE_DIR = Join-Path $Root 'cache\triton'
$env:TORCHINDUCTOR_CACHE_DIR = Join-Path $Root 'cache\inductor'
$env:MPLCONFIGDIR = Join-Path $Root 'cache\matplotlib'
$env:NUMBA_CACHE_DIR = Join-Path $Root 'cache\numba'
$env:MODELSCOPE_CACHE = Join-Path $Root 'cache\modelscope'
$env:XDG_CACHE_HOME = Join-Path $Root 'cache'
$env:GRADIO_ANALYTICS_ENABLED = 'False'
$env:HF_HUB_DISABLE_TELEMETRY = '1'
$env:HF_HUB_OFFLINE = $(if ($Online) { '0' } else { '1' })
$env:TRANSFORMERS_OFFLINE = $env:HF_HUB_OFFLINE
$env:PYTHONUNBUFFERED = '1'
$env:PYTHONIOENCODING = 'utf-8'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding
# Current API initialization also uses the runtime/checkpoints location directly.
$checkpoints = Join-Path $runtime 'checkpoints'
if (!(Test-Path -LiteralPath $checkpoints)) {
    New-Item -ItemType Junction -Path $checkpoints -Target (Join-Path $EngineRoot 'models') | Out-Null
}
# API job caches can contain prompts, so keep them with the other data on K:.
$runtimeCache = Join-Path $runtime '.cache'
$dataCache = Join-Path $Root 'cache\ace-runtime'
New-Item -ItemType Directory -Force $dataCache | Out-Null
if (!(Test-Path -LiteralPath $runtimeCache)) {
    New-Item -ItemType Junction -Path $runtimeCache -Target $dataCache | Out-Null
}
Push-Location $runtime
try {
    # Windows PowerShell treats native stderr as ErrorRecord; engine logs use it normally.
    $ErrorActionPreference = 'Continue'
    $entryPoint = Join-Path $PSScriptRoot 'music_runtime_fixes.py'
    if (!(Test-Path -LiteralPath $entryPoint)) { throw 'Missing music_runtime_fixes.py; run setup-music.ps1' }
    & $python $entryPoint --host 127.0.0.1 --port 8001 2>&1 | Tee-Object -FilePath (Join-Path $Root 'logs\ace-step.log') -Append
    if ($LASTEXITCODE -ne 0) { throw "ACE-Step exited with code $LASTEXITCODE" }
} finally { Pop-Location }
