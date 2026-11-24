#!/usr/bin/env bash
# Autor: Gabriel Martins de Almeida
# RA: 25006162

# Prepara e inicia o ConsultaFácil inteiro com um comando: MongoDB, servidor de CPF, API e frontend.
#
# Uso (a partir da raiz do projeto, no Linux, macOS ou Git Bash):
#   bash scripts/iniciar.sh             # prepara o que faltar e inicia tudo
#   bash scripts/iniciar.sh --recriar   # também apaga e recarrega o banco do .env com os dados de exemplo
#
# Na primeira execução, o script cria o .env a partir do .env.example, sobe o MongoDB, carrega os dados
# de exemplo se o banco estiver vazio, instala o módulo server/ e as dependências do frontend.
# O servidor de CPF e a API rodam em segundo plano, com saída em logs/. O frontend roda neste terminal.
# Ctrl+C encerra os três processos. O MongoDB continua no ar (para parar: docker compose stop mongo).
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$RAIZ"
CONTAINER="${MONGO_CONTAINER:-consultafacil-mongo}"
LOGS="$RAIZ/logs"
RECRIAR=false

for arg in "$@"; do
  case "$arg" in
    --recriar) RECRIAR=true ;;
    -h|--help) sed -n '/^# Prepara/,/^# Ctrl+C/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Opção desconhecida: $arg (use --recriar ou --help)" >&2; exit 1 ;;
  esac
done

etapa() { printf '\n==> %s\n' "$1"; }
falha() { printf '\nERRO: %s\n' "$1" >&2; exit 1; }

# Compara versões no formato X.Y.Z: retorna 0 se $1 >= $2.
versao_minima() {
  [ "$(printf '%s\n%s\n' "$2" "$1" | sort -t. -k1,1n -k2,2n -k3,3n | head -n1)" = "$2" ]
}

# Lê uma variável do .env (sem interpretar o arquivo como script).
ler_env() {
  local valor
  valor="$(grep -E "^$1=" .env | tail -n1 | cut -d= -f2- | tr -d '\r' || true)"
  printf '%s' "${valor:-$2}"
}

# Encerra os processos iniciados em segundo plano quando o script termina.
PIDS=()
encerrar() {
  trap - EXIT INT TERM
  if [ "${#PIDS[@]}" -gt 0 ]; then
    echo
    echo "Encerrando servidor de CPF e API..."
    kill "${PIDS[@]}" 2>/dev/null || true
    wait "${PIDS[@]}" 2>/dev/null || true
  fi
}
trap encerrar EXIT
trap 'exit 130' INT TERM

# Espera uma mensagem aparecer no log de um processo, falhando se ele terminar antes.
esperar_log() {
  local pid="$1" arquivo="$2" texto="$3" limite="$4" nome="$5" i=0
  until grep -q "$texto" "$arquivo" 2>/dev/null; do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "--- últimas linhas de $arquivo ---" >&2
      tail -n 30 "$arquivo" >&2
      falha "$nome terminou antes de ficar pronto. Veja o log completo em $arquivo"
    fi
    i=$((i + 1))
    [ "$i" -le "$limite" ] || falha "$nome não ficou pronto em ${limite}s. Veja $arquivo"
    sleep 1
  done
}

porta_ocupada() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  elif command -v nc >/dev/null 2>&1; then
    nc -z localhost "$1" >/dev/null 2>&1
  else
    return 1
  fi
}

# ---------------------------------------------------------------------------
etapa "1/6 Conferindo pré-requisitos"

command -v java >/dev/null 2>&1 || falha "Java não encontrado. Instale o JDK 21."
JAVA_VERSAO="$(java -version 2>&1 | grep -m1 -i 'version' | sed -E 's/.*version "([0-9]+)[^"]*".*/\1/')"
[ "${JAVA_VERSAO:-0}" -ge 21 ] 2>/dev/null || falha "Java 21 ou superior é necessário (encontrado: ${JAVA_VERSAO:-desconhecido})."
echo "Java $JAVA_VERSAO"

command -v node >/dev/null 2>&1 || falha "Node.js não encontrado. Instale o Node 22.22 ou superior."
NODE_VERSAO="$(node -p 'process.versions.node')"
versao_minima "$NODE_VERSAO" "22.22.0" || falha "Node 22.22 ou superior é necessário (encontrado: $NODE_VERSAO)."
command -v npm >/dev/null 2>&1 || falha "npm não encontrado. Ele vem junto com o Node.js."
echo "Node $NODE_VERSAO"

command -v docker >/dev/null 2>&1 || falha "Docker não encontrado. Instale o Docker Desktop."
docker info >/dev/null 2>&1 || falha "O Docker não está respondendo. Abra o Docker Desktop, espere ele iniciar e rode o script de novo."
echo "Docker em execução"

# ---------------------------------------------------------------------------
etapa "2/6 Arquivo .env"
if [ -f .env ]; then
  echo ".env já existe, mantido como está."
else
  cp .env.example .env
  echo ".env criado a partir do .env.example."
fi
BANCO="$(ler_env MONGODB_DATABASE consultafacil)"
API_PORTA="$(ler_env SERVER_PORT 8080)"
CPF_PORTA="$(ler_env CPF_SERVER_PORT 3001)"

for porta in "$CPF_PORTA" "$API_PORTA" 5173; do
  if porta_ocupada "$porta"; then
    falha "A porta $porta já está em uso. Feche o processo antigo (por exemplo, outro terminal com o projeto rodando) e tente de novo."
  fi
done

# ---------------------------------------------------------------------------
etapa "3/6 Banco de dados ($BANCO)"
docker compose up -d --wait mongo

case "$BANCO" in
  consultafacil|consultafacil_demo|consultafacil_test) BANCO_CONHECIDO=true ;;
  *) BANCO_CONHECIDO=false ;;
esac

if [ "$RECRIAR" = true ]; then
  [ "$BANCO_CONHECIDO" = true ] || falha "O banco '$BANCO' do .env não pode ser recriado pelo script (use consultafacil ou consultafacil_demo)."
  bash scripts/recriar-banco.sh "$BANCO"
else
  USUARIOS="$(docker exec -i "$CONTAINER" mongosh "$BANCO" --quiet --eval 'db.usuarios.countDocuments()' | tr -d '\r[:space:]')"
  if [ "${USUARIOS:-0}" = "0" ]; then
    if [ "$BANCO_CONHECIDO" = true ]; then
      echo "Banco $BANCO sem usuários. Carregando os dados de exemplo..."
      bash scripts/recriar-banco.sh "$BANCO"
    else
      echo "AVISO: o banco $BANCO está vazio e não é um dos bancos padrão; nada foi carregado." >&2
    fi
  else
    echo "Banco $BANCO já tem dados ($USUARIOS usuários), mantido como está. Para recarregar: bash scripts/iniciar.sh --recriar"
  fi
fi

# ---------------------------------------------------------------------------
etapa "4/6 Módulo de CPF (server/)"
echo "Compilando e instalando no Maven local (na primeira vez, o Maven baixa as dependências)..."
(cd server && ../backend/mvnw --quiet install -DskipTests) || falha "Falha ao instalar o módulo server/."

# ---------------------------------------------------------------------------
etapa "5/6 Dependências do frontend"
if [ ! -d frontend/node_modules ] || [ frontend/package-lock.json -nt frontend/node_modules ]; then
  (cd frontend && npm install) || falha "Falha no npm install."
  touch frontend/node_modules
else
  echo "Dependências já instaladas."
fi

# ---------------------------------------------------------------------------
etapa "6/6 Iniciando os processos"
mkdir -p "$LOGS"

# O servidor de CPF lê comandos do teclado e se desliga ao receber fim de entrada. Em segundo plano,
# a entrada seria vazia, então ela fica ligada a um laço que dura enquanto este script estiver rodando.
# Quando o script termina, a entrada fecha e o servidor se desliga sozinho, como no comando "desativar".
SCRIPT_PID=$$
while kill -0 "$SCRIPT_PID" 2>/dev/null; do sleep 1; done \
  | java -cp server/target/classes br.edu.puccampinas.consultafacil.Servidor "$CPF_PORTA" > "$LOGS/cpf.log" 2>&1 &
CPF_PID=$!
PIDS+=("$CPF_PID")
sleep 2
kill -0 "$CPF_PID" 2>/dev/null || { tail -n 20 "$LOGS/cpf.log" >&2; falha "O servidor de CPF não iniciou. Veja $LOGS/cpf.log"; }
echo "Servidor de CPF na porta $CPF_PORTA (log: logs/cpf.log)"

(cd backend && exec ./mvnw --quiet compile exec:java) > "$LOGS/api.log" 2>&1 &
API_PID=$!
PIDS+=("$API_PID")
echo "Iniciando a API (na primeira vez pode levar alguns minutos)..."
esperar_log "$API_PID" "$LOGS/api.log" "Servidor rodando na porta" 600 "A API"
echo "API na porta $API_PORTA (log: logs/api.log)"

cat <<MSG

Tudo pronto. Abra http://localhost:5173 e entre com:
  secretaria@consultafacil.demo / Demo@123
(outras contas no README, em "Demonstração com dados prontos")

O frontend roda abaixo. Ctrl+C encerra o frontend, a API e o servidor de CPF.

MSG

cd frontend
npm run dev
