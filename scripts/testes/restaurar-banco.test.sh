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
export BACKUP_DIR="$TEMP/backups"

for ((tentativa = 0; tentativa < 30; tentativa++)); do
  if docker exec "$CONTAINER" mongosh --quiet --eval 'db.adminCommand({ ping: 1 })' >/dev/null 2>&1; then break; fi
  sleep 1
done

mongo() {
  docker exec "$CONTAINER" mongosh "$1" --quiet --eval "$2"
}

mongo consultafacil_test '
  db.createCollection("sentinela", { validator: { valor: { $type: "string" } } });
  db.sentinela.createIndex({ valor: 1 }, { unique: true });
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

# Dois processos na mesma pasta e no mesmo segundo, inclusive em máquinas lentas.
mkdir "$TEMP/bin"
cat > "$TEMP/bin/date" <<'DATA'
#!/usr/bin/env bash
echo 20261119-120000
DATA
chmod +x "$TEMP/bin/date"
PATH="$TEMP/bin:$PATH" bash "$SCRIPTS/backup-banco.sh" consultafacil_demo & pid1=$!
PATH="$TEMP/bin:$PATH" bash "$SCRIPTS/backup-banco.sh" consultafacil_demo & pid2=$!
wait "$pid1"
wait "$pid2"
arquivos=("$BACKUP_DIR"/consultafacil_demo-20261119-120000-*.archive.gz)
[ "${#arquivos[@]}" -eq 2 ]
for arquivo in "${arquivos[@]}"; do
  bash "$SCRIPTS/restaurar-banco.sh" "$arquivo" concorrente_recuperacao --sim
  mongo concorrente_recuperacao 'if (db.novo.findOne({ _id: "backup" })?.valor !== "restaurado") quit(1)'
  mongo concorrente_recuperacao 'db.dropDatabase()'
done

# Intercepta apenas a restauração final. Validação, drop e retorno usam MongoDB real.
export DOCKER_REAL="$(command -v docker)"
cat > "$TEMP/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -eu
if [[ " $* " == *" mongorestore "* && " $* " == *' --nsTo consultafacil_test.* '* ]]; then
  "$DOCKER_REAL" exec "$MONGO_CONTAINER" mongosh consultafacil_test --quiet --eval '
    if (db.getCollectionNames().length !== 0) quit(2);
    db.parcial.insertOne({ _id: "incompleto" });
  '
  echo "Falha simulada após escrita parcial" >&2
  exit 42
fi
if [[ "${FALHAR_RETORNO:-}" == sim && " $* " == *" mongorestore "* && " $* " == *' --nsInclude consultafacil_test.* '* ]]; then
  exit 43
fi
exec "$DOCKER_REAL" "$@"
DOCKER
chmod +x "$TEMP/bin/docker"
if PATH="$TEMP/bin:$PATH" ORIGEM=consultafacil_demo bash "$SCRIPTS/restaurar-banco.sh" "$VALIDO" consultafacil_test --sim > "$TEMP/retorno.log" 2>&1; then
  echo "A falha da restauração final deveria ter sido reportada." >&2
  exit 1
fi
grep -q 'Falha simulada após escrita parcial' "$TEMP/retorno.log"
grep -q 'Estado anterior de consultafacil_test recuperado' "$TEMP/retorno.log"
mongo consultafacil_test '
  const colecoes = db.getCollectionInfos();
  if (colecoes.length !== 1 || colecoes[0].name !== "sentinela"
      || colecoes[0].options.validator.valor.$type !== "string"
      || !db.sentinela.getIndexes().some(i => i.key.valor === 1 && i.unique)
      || db.sentinela.findOne({ _id: "original" })?.valor !== "preservado") quit(1);
'
verificar_limpeza
echo "Backups simultâneos restaurados separadamente e destino recuperado após falha parcial."

# Se o próprio retorno falhar, o arquivo fica disponível após a saída do script.
if PATH="$TEMP/bin:$PATH" FALHAR_RETORNO=sim ORIGEM=consultafacil_demo bash "$SCRIPTS/restaurar-banco.sh" "$VALIDO" consultafacil_test --sim > "$TEMP/manual.log" 2>&1; then
  exit 1
fi
grep -q 'Recuperação automática incompleta' "$TEMP/manual.log"
retidos=("$BACKUP_DIR"/retorno.*/consultafacil_test-anterior.archive.gz)
[ "${#retidos[@]}" -eq 1 ] && [ -s "${retidos[0]}" ] || exit 1
ORIGEM=consultafacil_test bash "$SCRIPTS/restaurar-banco.sh" "${retidos[0]}" consultafacil_test --sim
mongo consultafacil_test 'if (db.sentinela.findOne({ _id: "original" })?.valor !== "preservado") quit(1)'
echo "Cópia de retorno preservada e recuperada manualmente."

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
