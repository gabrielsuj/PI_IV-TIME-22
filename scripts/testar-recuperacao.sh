#!/usr/bin/env bash
# Teste de recuperação (#45): faz o backup de um banco, restaura numa cópia temporária
# (<banco>_recuperacao) e confere, coleção por coleção, documentos, conteúdo (hash),
# índices e validadores. O banco original não é alterado; a cópia é apagada no final.
#
# Uso (a partir da raiz do projeto, com o container no ar):
#   bash scripts/testar-recuperacao.sh                  # consultafacil_demo
#   bash scripts/testar-recuperacao.sh consultafacil
set -euo pipefail

BANCO="${1:-consultafacil_demo}"
COPIA="${BANCO}_recuperacao"
CONTAINER="${MONGO_CONTAINER:-consultafacil-mongo}"
DIR="$(cd "$(dirname "$0")" && pwd)"
TEMP="$(mktemp -d)"

limpar() {
  rm -rf "$TEMP"
  docker exec "$CONTAINER" mongosh "$COPIA" --quiet --eval 'db.dropDatabase()' > /dev/null 2>&1 || true
}
trap limpar EXIT

echo "1/3 Backup de $BANCO"
BACKUP_DIR="$TEMP" bash "$DIR/backup-banco.sh" "$BANCO" > /dev/null
ARQUIVO="$(ls "$TEMP"/*.archive.gz)"
echo "    $(du -h "$ARQUIVO" | cut -f1) gerados"

echo "2/3 Restauração na cópia temporária $COPIA"
bash "$DIR/restaurar-banco.sh" "$ARQUIVO" "$COPIA" --sim > /dev/null

echo "3/3 Comparação do original com a cópia restaurada"
docker exec "$CONTAINER" mongosh --quiet --eval "
  const original = db.getSiblingDB('$BANCO'), copia = db.getSiblingDB('$COPIA');
  const hashes = (d) => d.runCommand({ dbHash: 1 }).collections;
  const indices = (d, c) => JSON.stringify(d.getCollection(c).getIndexes()
    .map(i => ({ nome: i.name, chave: i.key, unico: !!i.unique, parcial: i.partialFilterExpression || null }))
    .sort((a, b) => a.nome.localeCompare(b.nome)));
  const validador = (d, c) => { const info = d.getCollectionInfos({ name: c })[0];
    return EJSON.stringify((info && info.options && info.options.validator) || null); };
  const ha = hashes(original), hb = hashes(copia);
  const nomes = [...new Set([...Object.keys(ha), ...Object.keys(hb)])].sort();
  let falhas = 0;
  print('    ' + 'coleção'.padEnd(16) + 'documentos'.padEnd(12) + 'conteúdo  índices  validador');
  for (const c of nomes) {
    const n = original.getCollection(c).countDocuments(), m = copia.getCollection(c).countDocuments();
    const ok = [n === m, ha[c] === hb[c], indices(original, c) === indices(copia, c), validador(original, c) === validador(copia, c)];
    if (ok.includes(false)) falhas++;
    const marca = (b) => (b ? 'ok' : 'DIFERE');
    print('    ' + c.padEnd(16) + (n + (n === m ? '' : ' ≠ ' + m)).padEnd(12) + marca(ok[1]).padEnd(10) + marca(ok[2]).padEnd(9) + marca(ok[3]));
  }
  if (nomes.length === 0) { print('    O banco $BANCO está vazio: nada a comparar.'); quit(1); }
  print(falhas === 0
    ? '\nRecuperação verificada: o backup restaura ' + nomes.length + ' coleções idênticas ao original.'
    : '\n' + falhas + ' coleção(ões) diferem do original.');
  if (falhas > 0) quit(1);
"
