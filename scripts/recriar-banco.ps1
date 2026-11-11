# Recria somente o banco selecionado. Execute com o container Mongo em funcionamento.
# Exemplo: .\scripts\recriar-banco.ps1 consultafacil_demo
param(
    [ValidateSet('consultafacil', 'consultafacil_test', 'consultafacil_demo')]
    [string]$Banco = 'consultafacil_demo'
)

$ErrorActionPreference = 'Stop'
$Container = if ($env:MONGO_CONTAINER) { $env:MONGO_CONTAINER } else { 'consultafacil-mongo' }
$Raiz = Split-Path -Parent $PSScriptRoot
$EncodingAnterior = $OutputEncoding

function Invoke-MongoScript([string]$Codigo) {
    $Codigo | docker exec -i $Container mongosh $Banco --quiet --file /dev/stdin
    if ($LASTEXITCODE -ne 0) { throw "Falha ao executar script no banco $Banco." }
}

try {
    # Preserva nomes com acentos também no Windows PowerShell 5.1.
    $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $Rodando = docker inspect --format '{{.State.Running}}' $Container
    if ($LASTEXITCODE -ne 0 -or $Rodando -ne 'true') {
        throw "Container $Container indisponivel. Execute: docker compose up -d mongo"
    }

    Invoke-MongoScript 'if (db.runCommand({ ping: 1 }).ok !== 1) throw new Error("Mongo indisponivel");'
    Write-Host "Apagando $Banco..."
    Invoke-MongoScript 'if (db.dropDatabase().ok !== 1) throw new Error("Falha ao apagar banco");'

    $Pastas = @('database/init')
    if ($Banco -ne 'consultafacil_test') { $Pastas += 'database/seed' }
    foreach ($Pasta in $Pastas) {
        $Arquivos = Get-ChildItem -LiteralPath (Join-Path $Raiz $Pasta) -Filter '*.js' -File | Sort-Object Name
        foreach ($Arquivo in $Arquivos) {
            Write-Host "  script: $($Arquivo.Name)"
            Invoke-MongoScript (Get-Content -LiteralPath $Arquivo.FullName -Raw -Encoding UTF8)
        }
    }
    Write-Host "Banco $Banco recriado."
} finally {
    $OutputEncoding = $EncodingAnterior
}
