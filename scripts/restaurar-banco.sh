#!/usr/bin/env bash
# Restaura um backup gerado por scripts/backup-banco.sh (#45).
# O banco de destino é apagado e recriado exatamente como estava no backup (dados, índices e validadores).
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

docker exec "$CONTAINER" mongosh "$DESTINO" --quiet --eval 'db.dropDatabase()' > /dev/null
docker exec -i "$CONTAINER" mongorestore --quiet --archive --gzip \
  --nsInclude "$ORIGEM.*" --nsFrom "$ORIGEM.*" --nsTo "$DESTINO.*" < "$ARQUIVO"

echo "Backup $NOME restaurado em $DESTINO:"
docker exec "$CONTAINER" mongosh "$DESTINO" --quiet --eval '
  db.getCollectionNames().sort().forEach(c => print("  " + c.padEnd(16) + db.getCollection(c).countDocuments()))'
