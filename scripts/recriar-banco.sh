#!/usr/bin/env bash
# Recria um banco do ConsultaFácil do zero: apaga, executa database/init/*.js
# e, exceto no banco de testes, carrega database/seed/*.js.
#
# Uso (a partir da raiz do projeto, com o container no ar):
#   scripts/recriar-banco.sh                      # consultafacil (desenvolvimento)
#   scripts/recriar-banco.sh consultafacil_test   # testes (sem dados de exemplo)
#   scripts/recriar-banco.sh consultafacil_demo   # demonstração
set -euo pipefail

BANCO="${1:-consultafacil}"
CONTAINER="${MONGO_CONTAINER:-consultafacil-mongo}"
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"

case "$BANCO" in
  consultafacil|consultafacil_test|consultafacil_demo) ;;
  *) echo "Banco inválido: $BANCO (use consultafacil, consultafacil_test ou consultafacil_demo)" >&2; exit 1 ;;
esac

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "Container $CONTAINER não está rodando. Execute: docker compose up -d mongo" >&2
  exit 1
fi

mongo() { docker exec -i "$CONTAINER" mongosh "$BANCO" --quiet "$@"; }

echo "Apagando $BANCO..."
mongo --eval 'db.dropDatabase()' > /dev/null

for f in "$RAIZ"/database/init/*.js; do
  echo "  init: $(basename "$f")"
  mongo --file /dev/stdin < "$f" > /dev/null
done

if [ "$BANCO" != "consultafacil_test" ]; then
  for f in "$RAIZ"/database/seed/*.js; do
    echo "  seed: $(basename "$f")"
    mongo --file /dev/stdin < "$f" > /dev/null
  done
fi

echo "Banco $BANCO recriado."
