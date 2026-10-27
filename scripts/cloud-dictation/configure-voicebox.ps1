# Configura o Voicebox (fork wisprclone) para ditado em nuvem via API local:
# transcricao no OpenRouter, polimento no Claude Haiku, idioma portugues.
# Le as chaves das variaveis de ambiente do usuario (set-keys.ps1) e nunca
# as imprime. Requer o app aberto (servidor em 127.0.0.1:17493).

param(
    [string]$Server = 'http://127.0.0.1:17493',
    [string]$SttEndpoint = 'https://openrouter.ai/api/v1',
    [string]$SttModel = 'openai/whisper-large-v3-turbo',
    [string]$LlmEndpoint = 'https://api.anthropic.com/v1',
    [string]$LlmModel = 'claude-haiku-5-5',
    [string]$Language = 'pt',
    [int]$WaitSeconds = 180
)

$ErrorActionPreference = 'Stop'

$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ($true) {
    try {
        Invoke-RestMethod -Uri "$Server/health" -TimeoutSec 3 | Out-Null
        break
    } catch {
        if ((Get-Date) -gt $deadline) { throw "Servidor do Voicebox nao respondeu em $Server - o app esta aberto?" }
        Start-Sleep -Seconds 3
    }
}
Write-Host "Servidor do Voicebox respondendo em $Server"

$sttKey = [Environment]::GetEnvironmentVariable('OPENROUTER_API_KEY', 'User')
$llmKey = [Environment]::GetEnvironmentVariable('ANTHROPIC_API_KEY', 'User')
if (-not $sttKey -or -not $llmKey) { throw 'Faltam OPENROUTER_API_KEY ou ANTHROPIC_API_KEY - rode tools\set-keys.ps1' }

$body = @{
    custom_stt_endpoint = $SttEndpoint
    custom_stt_model    = $SttModel
    custom_stt_api_key  = $sttKey
    custom_llm_endpoint = $LlmEndpoint
    custom_llm_model    = $LlmModel
    custom_llm_api_key  = $llmKey
    language            = $Language
    auto_refine         = $true
} | ConvertTo-Json
$sttKey = $null; $llmKey = $null

$saved = Invoke-RestMethod -Method Put -Uri "$Server/settings/captures" -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$body = $null

Write-Host ''
Write-Host 'Configuracao gravada:'
Write-Host ('  transcricao: {0} | {1} | chave salva: {2}' -f $saved.custom_stt_endpoint, $saved.custom_stt_model, $saved.custom_stt_api_key_configured)
Write-Host ('  polimento:   {0} | {1} | chave salva: {2}' -f $saved.custom_llm_endpoint, $saved.custom_llm_model, $saved.custom_llm_api_key_configured)
Write-Host ('  idioma: {0} | polimento automatico: {1} | atalho global: {2}' -f $saved.language, $saved.auto_refine, $saved.hotkey_enabled)

$ready = Invoke-RestMethod -Uri "$Server/capture/readiness"
Write-Host ('  prontidao: STT {0} ({1}) | LLM {2} ({3})' -f $ready.stt.ready, $ready.stt.display_name, $ready.llm.ready, $ready.llm.display_name)
