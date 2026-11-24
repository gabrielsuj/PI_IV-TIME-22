<!--
Autoria: Gabriel Martins de Almeida
RA: 25006162
-->

# Banco de dados: atualização, verificação e MongoDB local

Procedimentos de manutenção do MongoDB. Para preparar o banco na primeira execução, basta o [início rápido do README](../../README.md#início-rápido).

## 1. Como o banco é inicializado

- **Volume novo do Docker:** na primeira vez que o container sobe, os scripts de `database/init/` criam as coleções, os validadores (`$jsonSchema`) e os índices no banco `consultafacil`, definido pelo `docker-compose.yml`. Os seeds **não** são carregados, e os scripts só rodam com o volume vazio.
- **`scripts/recriar-banco.sh <banco>` (ou `.ps1`):** apaga somente o banco informado, reaplica `database/init/` e, exceto em `consultafacil_test`, carrega `database/seed/` em ordem. Os dois scripts aceitam apenas `consultafacil`, `consultafacil_test` e `consultafacil_demo` e recusam outros nomes antes de apagar qualquer dado.
- Mudar `MONGODB_DATABASE` no `.env` não inicializa o novo banco. Rode o `recriar-banco` para ele.

```bash
bash scripts/recriar-banco.sh consultafacil        # desenvolvimento
bash scripts/recriar-banco.sh consultafacil_test   # testes (sem dados de exemplo)
bash scripts/recriar-banco.sh consultafacil_demo   # demonstração
```

No PowerShell: `.\scripts\recriar-banco.ps1 <banco>` (se a política de execução bloquear, use `powershell -ExecutionPolicy Bypass -File .\scripts\recriar-banco.ps1 <banco>`).

Os seeds usam IDs fixos e podem ser repetidos sem duplicar registros. O seed de usuários preserva as senhas existentes, e a recriação completa restaura a senha de demonstração (`Demo@123`). As senhas são gravadas com PBKDF2, compatível com o `SenhaHasher` da API. O login da médica está vinculado ao cadastro de Ana, e Paulo aparece tanto como usuário quanto como paciente, com o mesmo CPF e e-mail.

## 2. Atualizar um banco que já existe (preservando os dados)

O Docker não reaplica os scripts num volume já populado. Quando os schemas ou os índices mudarem, faça o seguinte:

1. Guarde um backup: `bash scripts/backup-banco.sh <banco>`.
2. Confira a restauração em uma cópia temporária, sem alterar o original: `bash scripts/testar-recuperacao.sh <banco>` (veja [backup e recuperação](backup-recuperacao.md)).
3. Com o container saudável, aplique todos os scripts `01` a `10`, em ordem, em cada banco utilizado.

Bash:

```bash
(
  banco=consultafacil_demo # escolha explicitamente o banco
  for f in database/init/*.js; do
    docker exec -i consultafacil-mongo mongosh "$banco" --quiet --file /dev/stdin < "$f" || {
      printf 'Falha ao aplicar %s no banco %s\n' "$f" "$banco" >&2
      exit 1
    }
  done
)
```

PowerShell:

```powershell
$banco = 'consultafacil_demo' # escolha explicitamente o banco
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Get-ChildItem database/init/*.js | Sort-Object Name | ForEach-Object {
    Get-Content -Raw -Encoding UTF8 $_.FullName | docker exec -i consultafacil-mongo mongosh $banco --quiet --file /dev/stdin
    if ($LASTEXITCODE -ne 0) { throw "Falha em $($_.Name)" }
}
```

Os scripts atualizam validadores e índices e preservam os documentos (`02` mantém agendas e bloqueios, `06` especialidades e `07` médicos). Não apague o volume para atualizar schemas. Use o `recriar-banco` apenas quando os dados puderem ser substituídos.

## 3. Conferir a modelagem e a carga de demonstração

Com `consultafacil_demo` recriado (schemas e seeds aplicados):

```bash
docker exec -i consultafacil-mongo mongosh consultafacil_demo --quiet --file /dev/stdin < scripts/verificar-modelagem.js
docker exec -i consultafacil-mongo mongosh consultafacil_demo --quiet --file /dev/stdin < scripts/verificar-carga.js
```

O primeiro confere validadores e índices. O segundo confere a coerência da carga (jornadas, bloqueios, sobreposições, histórico e logins). O healthcheck do Docker só verifica se o MongoDB responde, não os schemas, os índices ou os seeds. Detalhes da modelagem em [medicos-agenda.md](../dicionario/medicos-agenda.md) e [consultas.md](../dicionario/consultas.md).

## 4. Usar um MongoDB instalado na máquina (sem Docker)

Os scripts de `scripts/` usam `docker exec` e só funcionam com o container `consultafacil-mongo`. Com um MongoDB 7 local e o `mongosh` instalado, aplique os scripts diretamente:

```bash
banco=consultafacil
mongosh "$banco" --quiet --eval 'db.dropDatabase()'      # apaga o banco escolhido
for f in database/init/*.js database/seed/*.js; do       # para consultafacil_test, use só database/init
  mongosh "$banco" --quiet --file "$f" || { echo "Falha em $f"; break; }
done
```

Depois, ajuste `MONGODB_URI` no `.env` se o servidor não estiver em `localhost:27017`.
