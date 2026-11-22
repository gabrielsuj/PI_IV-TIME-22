#!/usr/bin/env bash
# Autor: Gabriel Martins de Almeida
# RA: 25006162

# Restaura um backup gerado por scripts/backup-banco.sh (#45).
# Valida o backup em um banco temporário antes de substituir o banco de destino.
#
# Uso (a partir da raiz do projeto, com o container no ar):
#   bash scripts/restaurar-banco.sh backups/consultafacil_demo-20261120-1830.archive.gz
#   bash scripts/restaurar-banco.sh <arquivo> consultafacil      # restaura em outro banco
#   bash scripts/restaurar-banco.sh <arquivo> --sim              # sem pedir confirmação
set -euo pipefail

CONTAINER="${MONGO_CONTAINER:-consultafacil-mongo}"
ARQUIVO="" DESTINO="" CONFIRMADO=false
for arg in "$@"; do
  case "$arg" in
    --sim|-y) CONFIRMADO=true ;;
    *) if [ -z "$ARQUIVO" ]; then ARQUIVO="$arg"; else DESTINO="$arg"; fi ;;
  esac
done

if [ -z "$ARQUIVO" ] || [ ! -s "$ARQUIVO" ]; then
  echo "Informe um arquivo de backup existente. Ex.: bash scripts/restaurar-banco.sh backups/consultafacil_demo-20261120-183000.archive.gz" >&2
  exit 1
fi

# O banco de origem vem do nome do arquivo: <banco>-AAAAMMDD-HHMMSS.archive.gz
NOME="$(basename "$ARQUIVO")"
ORIGEM="${ORIGEM:-${NOME%%-[0-9]*}}"
DESTINO="${DESTINO:-$ORIGEM}"

case "$ORIGEM" in
  consultafacil|consultafacil_test|consultafacil_demo) ;;
  *) echo "Não foi possível identificar o banco de origem pelo nome do arquivo ($NOME). Defina ORIGEM=<banco>." >&2; exit 1 ;;
esac
case "$DESTINO" in
  consultafacil|consultafacil_test|consultafacil_demo|*_recuperacao) ;;
  *) echo "Banco de destino inválido: $DESTINO (use consultafacil, consultafacil_test ou consultafacil_demo)" >&2; exit 1 ;;
esac

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "Container $CONTAINER não está rodando. Execute: docker compose up -d mongo" >&2
  exit 1
fi

if [ "$CONFIRMADO" != true ]; then
  read -r -p "O banco $DESTINO será APAGADO e substituído pelo backup $NOME. Continuar? [s/N] " resposta
  case "$resposta" in s|S|sim|SIM) ;; *) echo "Restauração cancelada."; exit 0 ;; esac
fi

apagar_banco() {
  docker exec "$CONTAINER" mongosh "$1" --quiet --eval '
    const resultado = db.dropDatabase();
    if (resultado.ok !== 1) { printjson(resultado); quit(1); }
  ' > /dev/null
}

restaurar_em() {
  docker exec -i "$CONTAINER" mongorestore --quiet --archive --gzip --stopOnError \
    --nsInclude "$ORIGEM.*" --nsFrom "$ORIGEM.*" --nsTo "$1.*" < "$COPIA_BACKUP"
}

PASTA_VALIDACAO="$(mktemp -d "${TMPDIR:-/tmp}/consultafacil-restauracao.XXXXXXXX")"
COPIA_BACKUP="$PASTA_VALIDACAO/backup.archive.gz"
BANCO_VALIDACAO=""

# O arquivo de retorno precisa sobreviver também à queda do processo/host.
PASTA_RETORNO=""
RETORNO_PENDENTE=false
limpar() {
  local status=$?
  if [ "$RETORNO_PENDENTE" = true ]; then
    echo "Recuperando o estado anterior de $DESTINO..." >&2
    if apagar_banco "$DESTINO" && docker exec -i "$CONTAINER" mongorestore --quiet --archive --gzip --stopOnError \
        --nsInclude "$DESTINO.*" < "$ANTERIOR"; then
      echo "Estado anterior de $DESTINO recuperado." >&2
      RETORNO_PENDENTE=false
    else
      echo "Recuperação automática incompleta. Cópia anterior preservada em: $ANTERIOR" >&2
    fi
  fi
  if [ -n "$PASTA_RETORNO" ] && [ "$RETORNO_PENDENTE" = false ]; then
    rm -f -- "$ANTERIOR"
    rmdir -- "$PASTA_RETORNO"
  fi
  if [ -n "$BANCO_VALIDACAO" ] && ! apagar_banco "$BANCO_VALIDACAO"; then
    echo "Não foi possível remover o banco temporário $BANCO_VALIDACAO." >&2
  fi
  rm -f -- "$COPIA_BACKUP"
  rmdir -- "$PASTA_VALIDACAO"
  return "$status"
}
trap limpar EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# As duas restaurações usam os mesmos bytes, mesmo que o arquivo original seja substituído.
cp -- "$ARQUIVO" "$COPIA_BACKUP"
if ! gzip -t "$COPIA_BACKUP"; then
  echo "Backup inválido. O banco $DESTINO foi preservado." >&2
  exit 1
fi

BANCO_VALIDACAO="consultafacil_validacao_${PASTA_VALIDACAO##*.}"
echo "Validando o backup em um banco temporário..."
if ! restaurar_em "$BANCO_VALIDACAO" || ! docker exec "$CONTAINER" mongosh "$BANCO_VALIDACAO" --quiet --eval '
  if (db.getCollectionInfos().length === 0) {
    print("Nenhuma coleção do banco de origem foi restaurada.");
    quit(1);
  }
'; then
  echo "A validação do backup falhou. O banco $DESTINO foi preservado." >&2
  exit 1
fi

RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
PASTA_BACKUPS="${BACKUP_DIR:-$RAIZ/backups}"
mkdir -p -- "$PASTA_BACKUPS"
PASTA_RETORNO="$(mktemp -d "$PASTA_BACKUPS/retorno.XXXXXXXX")"
ANTERIOR="$PASTA_RETORNO/$DESTINO-anterior.archive.gz"
docker exec "$CONTAINER" mongodump --quiet --db "$DESTINO" --archive --gzip > "$ANTERIOR"
gzip -t "$ANTERIOR"
echo "Cópia do destino antes da substituição: $ANTERIOR"
RETORNO_PENDENTE=true
apagar_banco "$DESTINO"
restaurar_em "$DESTINO"
RETORNO_PENDENTE=false

echo "Backup $NOME restaurado em $DESTINO:"
docker exec "$CONTAINER" mongosh "$DESTINO" --quiet --eval '
  db.getCollectionNames().sort().forEach(c => print("  " + c.padEnd(16) + db.getCollection(c).countDocuments()))'
