# Backup e recuperação (#45)

Scripts para salvar e restaurar um banco do ConsultaFácil com um único comando, e para verificar que o backup realmente recupera os dados. Usam as ferramentas oficiais `mongodump` e `mongorestore`, que já vêm no container `mongo:7`.

Todos rodam a partir da raiz do projeto, com o container no ar (`docker compose up -d mongo`). No Windows, use o Git Bash.

## Comandos

| Ação | Comando | Resultado |
|---|---|---|
| Fazer backup | `bash scripts/backup-banco.sh [banco]` | Arquivo compactado em `backups/<banco>-AAAAMMDD-HHMMSS.archive.gz` com dados, índices e validadores. Padrão: `consultafacil_demo` |
| Restaurar | `bash scripts/restaurar-banco.sh <arquivo> [banco-destino] [--sim]` | **Apaga** o banco de destino e o recria exatamente como no backup. Pede confirmação; `--sim` dispensa. O destino padrão é o banco de origem, lido do nome do arquivo |
| Testar a recuperação | `bash scripts/testar-recuperacao.sh [banco]` | Faz o backup, restaura numa cópia temporária (`<banco>_recuperacao`), compara tudo e apaga a cópia. **Não altera o banco original** |

A pasta `backups/` está no `.gitignore`: os backups ficam só na máquina de quem os gerou.

## O que o teste de recuperação confere

Para cada coleção, o original e a cópia restaurada são comparados em:

- **documentos:** quantidade;
- **conteúdo:** hash do MongoDB (`dbHash`), que muda se qualquer campo de qualquer documento for diferente;
- **índices:** nome, chave, unicidade e filtro parcial, incluindo os índices de reserva;
- **validador:** o `$jsonSchema` da coleção.

Exemplo com a carga de demonstração:

```text
1/3 Backup de consultafacil_demo
    76K gerados
2/3 Restauração na cópia temporária consultafacil_demo_recuperacao
3/3 Comparação do original com a cópia restaurada
    coleção         documentos  conteúdo  índices  validador
    agendas         10          ok        ok       ok
    consultas       831         ok        ok       ok
    historico       1844        ok        ok       ok
    pacientes       150         ok        ok       ok
    …
Recuperação verificada: o backup restaura 10 coleções idênticas ao original.
```

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
