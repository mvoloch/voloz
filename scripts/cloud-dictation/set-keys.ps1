# Pede as chaves de API com entrada mascarada e grava como variaveis de
# ambiente do usuario (HKCU\Environment). Nao imprime as chaves: so o
# tamanho e o prefixo. Rode de novo para trocar uma chave; Enter vazio,
# ou uma chave com o prefixo errado, mantem a atual.

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
    if (-not $plain.StartsWith($Prefix)) {
        # Colar a chave errada no campo errado nao pode apagar a chave certa.
        Write-Host ('  NAO gravada: nao comeca com {0}. A chave atual foi mantida.' -f $Prefix)
        $plain = $null
        return
    }
    [Environment]::SetEnvironmentVariable($Name, $plain, 'User')
    Write-Host ('  gravada: {0} caracteres, prefixo {1} ok' -f $plain.Length, $Prefix)
    $plain = $null
}

Set-SecretEnv -Name 'OPENROUTER_API_KEY' -Prefix 'sk-or-' -Where 'openrouter.ai/settings/keys'
Set-SecretEnv -Name 'ANTHROPIC_API_KEY' -Prefix 'sk-ant-' -Where 'platform.claude.com/settings/keys, na organizacao ligada ao Max'
Set-SecretEnv -Name 'GROQ_API_KEY' -Prefix 'gsk_' -Where 'console.groq.com/keys'
Write-Host ''
Write-Host 'Pronto. Pode fechar esta aba e avisar no chat.'
