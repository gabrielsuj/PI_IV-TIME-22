<!--
Autoria: Gabriel Martins de Almeida
RA: 25006162
-->

# Alterações de agenda com consultas marcadas (#44)

Criar um bloqueio ou mudar a jornada de um médico pode atingir consultas já marcadas. Quando a mudança afeta consultas, a API devolve a lista antes de gravar, salvo quando o pedido já inclui a confirmação explícita. Consulte a [modelagem de agendas e bloqueios](../dicionario/medicos-agenda.md) e a [API de busca de vagas e agendamento](agendamento.md).

## Rotas e permissões

As rotas abaixo exigem `Authorization: Bearer <token>` e estão disponíveis para `MEDICO` e `SECRETARIA_ADMIN`. O médico usa `medicoId=meu` ou o ID do próprio cadastro. A secretaria informa o ID do profissional. Em `POST /bloqueios`, o campo `medicoId` vem no corpo JSON.

| Método | Caminho | Resposta de sucesso |
|---|---|---|
| GET | `/agendas?medicoId=<id>` | 200 com a configuração atual |
| PUT | `/agendas?medicoId=<id>` | 200 com a configuração salva |
| GET | `/bloqueios?medicoId=<id>[&de=AAAA-MM-DD&ate=AAAA-MM-DD]` | 200 com uma lista de bloqueios |
| POST | `/bloqueios` | 201 com o bloqueio criado |
| DELETE | `/bloqueios?id=<id>` | 200 com `{"removido":true}` |

A API retorna 400 para parâmetros ou corpo inválidos, 401 para sessão ausente ou expirada e 403 para perfil sem acesso, médico sem vínculo ou acesso à agenda de outro profissional. Retorna 404 se a agenda consultada ainda não foi configurada ou se o bloqueio a remover não existe na agenda autorizada. O filtro de acesso é aplicado antes da consulta de conflitos.

## Jornada semanal

`PUT /agendas` recebe a configuração semanal completa. O exemplo define atendimento às segundas, das 8h às 12h, com consultas de 30 minutos.

```json
{
  "fusoHorario": "America/Sao_Paulo",
  "duracaoMinutos": 30,
  "jornadas": [
    {"diaSemana": 1, "faixas": [{"inicioMinuto": 480, "fimMinuto": 720}]}
  ]
}
```

`fusoHorario` é opcional e assume `America/Sao_Paulo` quando omitido. A duração vai de 5 a 240 minutos e precisa caber em cada faixa. Os dias vão de 1 (segunda) a 7 (domingo). As faixas usam minutos desde a meia-noite e podem ser adjacentes. Uma lista de jornadas vazia representa uma agenda sem atendimento. Tanto o `GET` como o `PUT` devolvem os três campos acima mais `id` e `medicoId`, com dias e faixas ordenados.

## Bloqueios

`POST /bloqueios` recebe o intervalo no fuso do médico, com início incluído e fim excluído.

```json
{
  "medicoId": "100000000000000000000001",
  "inicio": "2026-11-16T09:00",
  "fim": "2026-11-16T10:00",
  "motivo": "Reunião"
}
```

O início deve preceder o fim e o motivo é opcional, com até 200 caracteres. A resposta acrescenta `id`, usa o identificador do médico mesmo quando o pedido enviou `meu` e devolve `null` se não houver motivo. A listagem devolve esses mesmos campos para cada registro. Para filtrar, envie `de` e `ate` juntos. As duas datas são inclusivas e a listagem inclui bloqueios que tocam o período. Bloqueios sobrepostos recebem 409 mesmo com confirmação.

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
