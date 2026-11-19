# Backup e recuperação (#45)

Scripts para salvar e restaurar um banco do ConsultaFácil com um único comando, e para verificar que o backup realmente recupera os dados. Usam as ferramentas oficiais `mongodump` e `mongorestore`, que já vêm no container `mongo:7`.

Todos rodam a partir da raiz do projeto, com o container no ar (`docker compose up -d mongo`). No Windows, use o Git Bash.

## Comandos

| Ação | Comando | Resultado |
|---|---|---|
| Fazer backup | `bash scripts/backup-banco.sh [banco]` | Arquivo compactado em `backups/<banco>-AAAAMMDD-HHMMSS-<sufixo>.archive.gz` com dados, índices e validadores. Padrão: `consultafacil_demo` |
| Restaurar | `bash scripts/restaurar-banco.sh <arquivo> [banco-destino] [--sim]` | Valida o backup em um banco temporário antes de apagar e recriar o destino. Pede confirmação, dispensada por `--sim`. Por padrão, o destino é o banco de origem, lido do nome do arquivo. |
| Testar a recuperação | `bash scripts/testar-recuperacao.sh [banco]` | Faz o backup, restaura numa cópia temporária (`<banco>_<sufixo>_recuperacao`), compara tudo e apaga a cópia. **Não altera o banco original** |

A pasta `backups/` está no `.gitignore`: os backups ficam só na máquina de quem os gerou.

A restauração copia o arquivo, confere a integridade do gzip e testa a cópia em um banco temporário. Se houver erro ou o arquivo não contiver coleções do banco de origem informado, o destino é preservado. Após a validação, o script usa a mesma cópia para restaurar o destino.

O teste `bash scripts/testes/restaurar-banco.test.sh` usa um container MongoDB descartável. Ele verifica que arquivos inválidos ou de outra origem preservam o destino e que um backup válido restaura os dados, os índices e os validadores.

## Falha durante a substituição do destino

Pare a API e outros processos que escrevam no destino antes de iniciar a restauração. Mantenha essa janela até conferir o resultado e execute apenas uma restauração por destino de cada vez. Reserve espaço para o banco de validação e para o dump do estado anterior.

Depois de validar o backup recebido, o script salva o destino em `backups/retorno.<sufixo>/<destino>-anterior.archive.gz` e imprime o caminho antes de apagar o banco. A variável `BACKUP_DIR` permite escolher outro diretório persistente. Se a gravação final falhar, o script limpa os dados parciais e restaura essa cópia. A saída continua indicando falha na substituição solicitada, mesmo quando o retorno é bem-sucedido.

Se o MongoDB permanecer indisponível durante o retorno ou o processo for interrompido à força, guarde o arquivo indicado no log. Para um destino com coleções, restabeleça o MongoDB, confira o nome do banco e recupere o arquivo. Exemplo para o banco de demonstração:

```bash
read -r -p "Caminho do arquivo de retorno: " arquivo
ORIGEM=consultafacil_demo bash scripts/restaurar-banco.sh "$arquivo" consultafacil_demo
```

Para os bancos de desenvolvimento e teste, ajuste a origem e o destino ao nome do banco anterior. Se o destino era originalmente vazio, o retorno consiste em remover as coleções parciais e conferir que o banco ficou vazio. O script remove sua cópia de retorno após sucesso ou recuperação automática bem-sucedida.

O teste força dois backups do mesmo banco no mesmo segundo e restaura cada arquivo. Também interrompe a gravação final depois de criar uma coleção parcial, verifica a recuperação do documento original, índice único e validador, e exercita o retorno manual quando a recuperação automática também falha.

## O que o teste de recuperação confere

Para cada coleção, o original e a cópia restaurada são comparados em:

- **documentos:** quantidade;
- **conteúdo:** hash do MongoDB (`dbHash`), que muda se qualquer campo de qualquer documento for diferente;
- **índices:** nome, chave, unicidade e filtro parcial, incluindo os índices de reserva;
- **validador:** o `$jsonSchema` da coleção.

### Verificação de 19/11/2026

Em um container descartável com MongoDB 7, foram aplicados os dez scripts de inicialização e os nove de carga. O verificador de coerência passou antes do backup. Com a aplicação parada, `bash scripts/testar-recuperacao.sh consultafacil_demo` gerou um arquivo de 76 KiB e restaurou as dez coleções em uma cópia temporária. Contagens, hashes, índices e validadores conferiram em todas elas.

| Coleção | Documentos recuperados | Coleção | Documentos recuperados |
|---|---:|---|---:|
| agendas | 10 | historico | 1.925 |
| bloqueios | 6 | medicos | 11 |
| consultas | 852 | notificacoes | 2 |
| especialidades | 9 | pacientes | 150 |
| filaEspera | 2 | usuarios | 15 |

As quantidades correspondem à carga dessa data. Em outro container, `bash scripts/testes/restaurar-banco.test.sh` confirmou a preservação do registro original após duas recusas (gzip íntegro com conteúdo inválido e archive de outra origem). O mesmo teste restaurou um backup válido com seu índice único e validador e verificou a limpeza dos bancos temporários.

O script termina com erro (código 1) se alguma coleção diferir, então pode ser usado em automação.

## Simulação de desastre

Validada com a carga de demonstração (MongoDB 7):

| Momento | Consultas | Pacientes | Histórico |
|---|---|---|---|
| Antes | 831 | 150 | 1.844 |
| Depois de apagar dados | 0 | 147 | 0 |
| Depois de `restaurar-banco.sh` | 831 | 150 | 1.844 |

Em seguida, o `scripts/verificar-carga.js` confirmou a coerência de todo o banco restaurado.

## Rotina sugerida para a entrega

1. Antes de apresentar ou entregar: `bash scripts/backup-banco.sh consultafacil_demo`.
2. Confirmar que o backup recupera: `bash scripts/testar-recuperacao.sh consultafacil_demo`.
3. Se algo der errado durante a apresentação: `bash scripts/restaurar-banco.sh backups/<arquivo> --sim`.

Para voltar a um banco "limpo" com a carga padrão, sem backup, continue usando `bash scripts/recriar-banco.sh consultafacil_demo`.
