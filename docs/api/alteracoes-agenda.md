# Alterações de agenda com consultas marcadas (#44)

Criar um bloqueio ou mudar a jornada de um médico pode atingir consultas já marcadas. A API não grava a mudança sem que a secretaria veja quais consultas serão afetadas.

## Quando acontece

| Operação | Consultas consideradas afetadas |
|---|---|
| `POST /bloqueios` | Consultas ativas (`AGENDADA`/`CONFIRMADA`) que tocam o período do bloqueio |
| `PUT /agendas` | Consultas ativas **futuras** que deixam de caber inteiras numa faixa do mesmo dia da semana na nova jornada (dia removido, faixa encurtada) |

Consultas canceladas, concluídas, faltas e consultas passadas não contam.

## Resposta 409

Sem confirmação, a API recusa e lista as consultas, no mesmo formato do calendário (`GET /consultas`):

```json
{
  "status": 409,
  "erro": "O período tem 2 consulta(s) marcada(s). Confirme para bloquear mesmo assim; elas precisarão ser remarcadas.",
  "consultas": [
    { "id": "…", "pacienteNome": "Paulo Paciente", "especialidadeNome": "Clínica Geral",
      "inicio": "2026-11-16T07:40", "fim": "2026-11-16T08:00", "status": "AGENDADA", "…": "…" }
  ]
}
```

## Confirmação

Repetindo a mesma chamada com **`?confirmar=true`**, a mudança é gravada. **As consultas continuam marcadas**: nada é cancelado automaticamente, porque o cancelamento registra no histórico quem cancelou e por quê. No calendário (#23), essas consultas aparecem como **"Remarcar"** até a secretaria remarcá-las ou cancelá-las.

Na interface, as telas de bloqueio e de jornada mostram a lista com as opções **Voltar e ajustar** e **Bloquear/Salvar mesmo assim**, e um link para o calendário do dia afetado.

## Implementação

- `service/ConsultasAfetadas`: busca as consultas ativas e as devolve com nomes e horário local.
- `BloqueioService.criar(…, confirmado)` e `AgendaService.salvar(…, confirmado)`: recusam com `ConflitoComConsultasException`.
- `AgendaHandler` e `BloqueioHandler`: transformam essa exceção no 409 com a lista; `?confirmar=true` é lido por `Query.confirmado`.
- Testes: `ConflitosAgendaIntegrationTest` (back) e `AgendaConflitos.test.jsx` (front).
