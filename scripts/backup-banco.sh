#!/usr/bin/env bash
# Backup completo de um banco do ConsultaFácil (dados, índices e validadores) em backups/ (#45).
# O arquivo é compactado e leva o banco e a data no nome: backups/<banco>-AAAAMMDD-HHMMSS.archive.gz
#
# Uso (a partir da raiz do projeto, com o container no ar):
#   bash scripts/backup-banco.sh                      # consultafacil_demo
#   bash scripts/backup-banco.sh consultafacil        # desenvolvimento
set -euo pipefail

BANCO="${1:-consultafacil_demo}"
CONTAINER="${MONGO_CONTAINER:-consultafacil-mongo}"
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
PASTA="${BACKUP_DIR:-$RAIZ/backups}"

case "$BANCO" in
  consultafacil|consultafacil_test|consultafacil_demo) ;;
  *) echo "Banco inválido: $BANCO (use consultafacil, consultafacil_test ou consultafacil_demo)" >&2; exit 1 ;;
esac

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "Container $CONTAINER não está rodando. Execute: docker compose up -d mongo" >&2
  exit 1
fi

mkdir -p "$PASTA"
ARQUIVO="$PASTA/$BANCO-$(date +%Y%m%d-%H%M%S).archive.gz"
trap 'rm -f "$ARQUIVO"; echo "Backup interrompido; nenhum arquivo foi mantido." >&2' ERR

echo "Gerando backup de $BANCO..."
docker exec "$CONTAINER" mongodump --quiet --db "$BANCO" --archive --gzip > "$ARQUIVO"
[ -s "$ARQUIVO" ] || { echo "O backup saiu vazio." >&2; false; }
trap - ERR

echo "Backup salvo em: ${ARQUIVO#"$RAIZ"/} ($(du -h "$ARQUIVO" | cut -f1))"
docker exec "$CONTAINER" mongosh "$BANCO" --quiet --eval '
  db.getCollectionNames().sort().forEach(c => print("  " + c.padEnd(16) + db.getCollection(c).countDocuments()))'
