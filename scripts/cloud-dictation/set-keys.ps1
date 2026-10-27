# Pede as chaves de API com entrada mascarada e grava como variaveis de
# ambiente do usuario (HKCU\Environment). Nao imprime as chaves: so o
# tamanho e se o prefixo confere. Rode de novo para trocar uma chave;
# Enter vazio mantem a atual.

function Set-SecretEnv {
    param([string]$Name, [string]$Prefix, [string]$Where)
    Write-Host ''
    Write-Host ('{0}  ({1})' -f $Name, $Where)
    $secure = Read-Host 'Cole a chave (nao aparece na tela) e tecle Enter' -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr).Trim()
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
    if (-not $plain) {
        Write-Host '  vazio - mantida como estava.'
        return
    }
    [Environment]::SetEnvironmentVariable($Name, $plain, 'User')
    $check = if ($plain.StartsWith($Prefix)) { 'ok' } else { 'NAO confere - verifique' }
    Write-Host ('  gravada: {0} caracteres, prefixo {1} {2}' -f $plain.Length, $Prefix, $check)
    $plain = $null
}

Set-SecretEnv -Name 'OPENROUTER_API_KEY' -Prefix 'sk-or-' -Where 'openrouter.ai/settings/keys'
Set-SecretEnv -Name 'ANTHROPIC_API_KEY' -Prefix 'sk-ant-' -Where 'platform.claude.com/settings/keys, na organizacao ligada ao Max'
Write-Host ''
Write-Host 'Pronto. Pode fechar esta aba e avisar no chat.'
