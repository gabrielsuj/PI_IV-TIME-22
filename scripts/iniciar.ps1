# Autor: Gabriel Martins de Almeida
# RA: 25006162

# Prepara e inicia o ConsultaFácil inteiro com um comando: MongoDB, servidor de CPF, API e frontend.
#
# Uso (a partir da raiz do projeto, no PowerShell do Windows):
#   .\scripts\iniciar.ps1            # prepara o que faltar e inicia tudo
#   .\scripts\iniciar.ps1 -Recriar   # também apaga e recarrega o banco do .env com os dados de exemplo
# Se a política de execução bloquear o script:
#   powershell -ExecutionPolicy Bypass -File .\scripts\iniciar.ps1
#
# Na primeira execução, o script cria o .env a partir do .env.example, sobe o MongoDB, carrega os dados
# de exemplo se o banco estiver vazio, instala o módulo server/ e as dependências do frontend.
# O servidor de CPF e a API abrem em janelas próprias do PowerShell. O frontend roda nesta janela.
# Ctrl+C encerra o frontend e fecha as janelas do servidor de CPF e da API.
# O MongoDB continua no ar (para parar: docker compose stop mongo).
param(
    [switch]$Recriar
)

# 'Continue' evita que o Windows PowerShell 5.1 trate como erro fatal o texto que java, docker e
# mvnw escrevem no stderr. Cada comando externo é conferido pelo $LASTEXITCODE.
$ErrorActionPreference = 'Continue'
$Raiz = Split-Path -Parent $PSScriptRoot
Set-Location $Raiz
$Container = if ($env:MONGO_CONTAINER) { $env:MONGO_CONTAINER } else { 'consultafacil-mongo' }
$Shell = (Get-Process -Id $PID).Path
$Janelas = @()

function Etapa([string]$Texto) { Write-Host ''; Write-Host "==> $Texto" -ForegroundColor Cyan }
function Falha([string]$Texto) { Write-Host ''; Write-Host "ERRO: $Texto" -ForegroundColor Red; exit 1 }

function Ler-Env([string]$Nome, [string]$Padrao) {
    $Linha = Get-Content .env | Where-Object { $_ -match "^$Nome=" } | Select-Object -Last 1
    if ($Linha) { return ($Linha -replace "^$Nome=", '').Trim() }
    return $Padrao
}

function Porta-Ocupada([int]$Porta) {
    if (-not (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) { return $false }
    return [bool](Get-NetTCPConnection -LocalPort $Porta -State Listen -ErrorAction SilentlyContinue)
}

function Abrir-Janela([string]$Titulo, [string]$Pasta, [string]$Comando) {
    $Script = "`$Host.UI.RawUI.WindowTitle = '$Titulo'; $Comando"
    return Start-Process -FilePath $Shell -ArgumentList '-NoExit', '-Command', $Script -WorkingDirectory $Pasta -PassThru
}

try {
    # -----------------------------------------------------------------------
    Etapa '1/6 Conferindo pré-requisitos'

    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Falha 'Java não encontrado. Instale o JDK 21.' }
    $JavaTexto = cmd /c 'java -version 2>&1' | Out-String
    $JavaVersao = 0
    if ($JavaTexto -match 'version "(\d+)') { $JavaVersao = [int]$Matches[1] }
    if ($JavaVersao -lt 21) { Falha "Java 21 ou superior é necessário (encontrado: $JavaVersao)." }
    Write-Host "Java $JavaVersao"

    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { Falha 'Node.js não encontrado. Instale o Node 22.22 ou superior.' }
    $NodeVersao = (node -p 'process.versions.node').Trim()
    if ([version]$NodeVersao -lt [version]'22.22.0') { Falha "Node 22.22 ou superior é necessário (encontrado: $NodeVersao)." }
    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) { Falha 'npm não encontrado. Ele vem junto com o Node.js.' }
    Write-Host "Node $NodeVersao"

    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Falha 'Docker não encontrado. Instale o Docker Desktop.' }
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { Falha 'O Docker não está respondendo. Abra o Docker Desktop, espere ele iniciar e rode o script de novo.' }
    Write-Host 'Docker em execução'

    # -----------------------------------------------------------------------
    Etapa '2/6 Arquivo .env'
    if (Test-Path .env) {
        Write-Host '.env já existe, mantido como está.'
    } else {
        Copy-Item .env.example .env
        Write-Host '.env criado a partir do .env.example.'
    }
    $Banco = Ler-Env 'MONGODB_DATABASE' 'consultafacil'
    $ApiPorta = Ler-Env 'SERVER_PORT' '8080'
    $CpfPorta = Ler-Env 'CPF_SERVER_PORT' '3001'

    foreach ($Porta in @($CpfPorta, $ApiPorta, '5173')) {
        if (Porta-Ocupada ([int]$Porta)) {
            Falha "A porta $Porta já está em uso. Feche o processo antigo (por exemplo, outra janela com o projeto rodando) e tente de novo."
        }
    }

    # -----------------------------------------------------------------------
    Etapa "3/6 Banco de dados ($Banco)"
    docker compose up -d --wait mongo
    if ($LASTEXITCODE -ne 0) { Falha 'O MongoDB não subiu. Confira a saída do docker compose acima.' }

    $BancoConhecido = @('consultafacil', 'consultafacil_demo', 'consultafacil_test') -contains $Banco
    $Carregar = $false
    if ($Recriar) {
        if (-not $BancoConhecido) { Falha "O banco '$Banco' do .env não pode ser recriado pelo script (use consultafacil ou consultafacil_demo)." }
        $Carregar = $true
    } else {
        $Usuarios = (docker exec -i $Container mongosh $Banco --quiet --eval 'db.usuarios.countDocuments()' | Out-String).Trim()
        if ($Usuarios -eq '0' -or $Usuarios -eq '') {
            if ($BancoConhecido) {
                Write-Host "Banco $Banco sem usuários. Carregando os dados de exemplo..."
                $Carregar = $true
            } else {
                Write-Host "AVISO: o banco $Banco está vazio e não é um dos bancos padrão; nada foi carregado." -ForegroundColor Yellow
            }
        } else {
            Write-Host "Banco $Banco já tem dados ($Usuarios usuários), mantido como está. Para recarregar: .\scripts\iniciar.ps1 -Recriar"
        }
    }
    if ($Carregar) {
        & (Join-Path $PSScriptRoot 'recriar-banco.ps1') $Banco
        if (-not $?) { Falha 'Falha ao carregar o banco.' }
    }

    # -----------------------------------------------------------------------
    Etapa '4/6 Módulo de CPF (server/)'
    Write-Host 'Compilando e instalando no Maven local (na primeira vez, o Maven baixa as dependências)...'
    Push-Location server
    & ..\backend\mvnw.cmd --quiet install -DskipTests
    $Codigo = $LASTEXITCODE
    Pop-Location
    if ($Codigo -ne 0) { Falha 'Falha ao instalar o módulo server/.' }

    # -----------------------------------------------------------------------
    Etapa '5/6 Dependências do frontend'
    $Modulos = Join-Path $Raiz 'frontend\node_modules'
    $Lock = Join-Path $Raiz 'frontend\package-lock.json'
    if (-not (Test-Path $Modulos) -or (Get-Item $Lock).LastWriteTime -gt (Get-Item $Modulos).LastWriteTime) {
        Push-Location frontend
        npm install
        $Codigo = $LASTEXITCODE
        Pop-Location
        if ($Codigo -ne 0) { Falha 'Falha no npm install.' }
        (Get-Item $Modulos).LastWriteTime = Get-Date
    } else {
        Write-Host 'Dependências já instaladas.'
    }

    # -----------------------------------------------------------------------
    Etapa '6/6 Iniciando os processos'

    $Janelas += Abrir-Janela 'ConsultaFácil - servidor de CPF' $Raiz "java -cp server/target/classes br.edu.puccampinas.consultafacil.Servidor $CpfPorta"
    Write-Host "Servidor de CPF iniciado em outra janela (porta $CpfPorta)."
    Start-Sleep -Seconds 2

    $Janelas += Abrir-Janela 'ConsultaFácil - API' (Join-Path $Raiz 'backend') '.\mvnw.cmd compile exec:java'
    Write-Host 'Iniciando a API em outra janela (na primeira vez pode levar alguns minutos)...'

    $Pronta = $false
    for ($i = 0; $i -lt 300; $i++) {
        try {
            Invoke-WebRequest "http://localhost:$ApiPorta/health" -UseBasicParsing -TimeoutSec 2 | Out-Null
            $Pronta = $true
            break
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    if (-not $Pronta) { Falha 'A API não respondeu em 10 minutos. Confira as mensagens na janela "ConsultaFácil - API".' }
    Write-Host "API na porta $ApiPorta."

    Write-Host ''
    Write-Host 'Tudo pronto. Abra http://localhost:5173 e entre com:' -ForegroundColor Green
    Write-Host '  secretaria@consultafacil.demo / Demo@123'
    Write-Host '(outras contas no README, em "Demonstração com dados prontos")'
    Write-Host ''
    Write-Host 'O frontend roda abaixo. Ctrl+C encerra o frontend e fecha as janelas do servidor de CPF e da API.'
    Write-Host ''

    Set-Location (Join-Path $Raiz 'frontend')
    npm run dev
} finally {
    Set-Location $Raiz
    foreach ($Janela in $Janelas) {
        if ($Janela -and -not $Janela.HasExited) {
            # /T encerra também os processos filhos (java e Maven) abertos pela janela.
            taskkill /PID $Janela.Id /T /F *> $null
        }
    }
    if ($Janelas.Count -gt 0) { Write-Host 'Servidor de CPF e API encerrados.' }
}
