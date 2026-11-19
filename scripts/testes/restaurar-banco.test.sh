#!/usr/bin/env bash
# Teste da restauração (#45) com MongoDB 7 em um container descartável.
set -euo pipefail

SCRIPTS="$(cd "$(dirname "$0")/.." && pwd)"
TEMP="$(mktemp -d)"
NOME_CONTAINER="consultafacil-teste-restauracao-${TEMP##*.}"
CONTAINER=""

limpar() {
  if [ -n "$CONTAINER" ]; then docker stop "$CONTAINER" >/dev/null; fi
  rm -rf -- "$TEMP"
}
trap limpar EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

docker run --rm --detach --name "$NOME_CONTAINER" mongo:7 >/dev/null
CONTAINER="$NOME_CONTAINER"
export MONGO_CONTAINER="$CONTAINER"

for ((tentativa = 0; tentativa < 30; tentativa++)); do
  if docker exec "$CONTAINER" mongosh --quiet --eval 'db.adminCommand({ ping: 1 })' >/dev/null 2>&1; then break; fi
  sleep 1
done

mongo() {
  docker exec "$CONTAINER" mongosh "$1" --quiet --eval "$2"
}

mongo consultafacil_test '
  db.sentinela.insertOne({ _id: "original", valor: "preservado" });
'
mongo consultafacil_demo '
  db.createCollection("novo", { validator: { valor: { $type: "string" } } });
  db.novo.createIndex({ valor: 1 }, { name: "valor_unico", unique: true });
  db.novo.insertOne({ _id: "backup", valor: "restaurado" });
'

verificar_limpeza() {
  mongo admin '
    const bancos = db.adminCommand({ listDatabases: 1, nameOnly: true }).databases;
    if (bancos.some(banco => banco.name.startsWith("consultafacil_validacao_"))) quit(1);
  '
}

esperar_falha() {
  if ORIGEM="$1" bash "$SCRIPTS/restaurar-banco.sh" "$2" consultafacil_test --sim > "$TEMP/erro.log" 2>&1; then
    echo "O script deveria recusar $2 com origem $1." >&2
    exit 1
  fi
  mongo consultafacil_test '
    const original = db.sentinela.findOne({ _id: "original" });
    if (original?.valor !== "preservado" || db.novo.countDocuments() !== 0) {
      print("A restauração recusada alterou o banco de destino.");
      quit(1);
    }
  '
  verificar_limpeza
}

# O gzip é íntegro, mas o conteúdo não é um archive do mongodump.
INVALIDO="$TEMP/consultafacil_demo-20261114-000000.archive.gz"
printf '%s\n' 'backup inválido' | gzip > "$INVALIDO"
esperar_falha consultafacil_demo "$INVALIDO"

VALIDO="$TEMP/valido.archive.gz"
docker exec "$CONTAINER" mongodump --quiet --db consultafacil_demo --archive --gzip > "$VALIDO"
# Um archive válido também deve ser recusado se não tiver coleções da origem informada.
esperar_falha consultafacil "$VALIDO"

ORIGEM=consultafacil_demo bash "$SCRIPTS/restaurar-banco.sh" "$VALIDO" consultafacil_test --sim
mongo consultafacil_test '
  const colecao = db.getCollectionInfos({ name: "novo" })[0];
  if (db.sentinela.findOne() !== null || db.novo.findOne({ _id: "backup" })?.valor !== "restaurado"
      || !db.novo.getIndexes().some(i => i.name === "valor_unico" && i.unique)
      || colecao?.options.validator.valor.$type !== "string") quit(1);
'
verificar_limpeza
echo "Restauração validada: backups recusados preservam o destino e o backup válido recupera dados, índice e validador."

# A comparação usa um banco exclusivo e preserva uma cópia anterior de nome fixo.
mongo consultafacil_demo_recuperacao 'db.sentinela.insertOne({ _id: "anterior" })'
bash "$SCRIPTS/testar-recuperacao.sh" consultafacil_demo
mongo consultafacil_demo_recuperacao 'if (!db.sentinela.findOne({ _id: "anterior" })) quit(1)'
mongo admin '
  const bancos = db.adminCommand({ listDatabases: 1, nameOnly: true }).databases;
  if (bancos.some(b => /^consultafacil_demo_.+_recuperacao$/.test(b.name))) quit(1);
'
