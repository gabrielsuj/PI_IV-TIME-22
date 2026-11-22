# API da central de notificações

> Issues #34 e #35 · Envio simulado, central de notificações e confirmação de presença

## 1. Rotas e permissões

As chamadas exigem o cabeçalho `Authorization: Bearer <token>`. O servidor usa o perfil do login e o cadastro de paciente ou médico vinculado a ele para localizar as notificações do destinatário. A secretaria não tem acesso a essas rotas.

| Método | Caminho | Resultado | Perfis autorizados |
|---|---|---|---|
| GET | `/notificacoes` | 200, lista de notificações | PACIENTE, MEDICO |
| POST | `/notificacoes/lida` | 204, leitura registrada | PACIENTE, MEDICO |
| POST | `/notificacoes/confirmar` | 204, presença confirmada | PACIENTE |

## 2. GET /notificacoes

A resposta inclui `Cache-Control: no-store`.

A lista inclui as notificações do destinatário com canal `SISTEMA` e status `ENVIADA`. O envio simulado produz confirmações de agendamento, lembretes e avisos de cancelamento. Cópias do canal `EMAIL_TESTE` ficam fora da central. A fila de espera tem uma rota própria para [avisos de vaga](fila-espera.md#7-avisos-de-vaga-e-agendamento-57).

A ordenação usa a entrega mais recente primeiro (`enviadaEm` decrescente) e o identificador decrescente em caso de empate. O endpoint devolve todos os itens elegíveis, sem filtros, paginação ou limite. A resposta sem itens é `[]`.

```json
[
  {
    "id": "650000000000000000000001",
    "titulo": "Lembrete de consulta",
    "mensagem": "Sua consulta com Juliana Ferraz é em 24/11 às 10:00. Confirme sua presença pelo ConsultaFácil.",
    "medicoNome": "Juliana Ferraz",
    "dataHora": "2026-11-24T13:00:00Z",
    "lida": false,
    "presencaConfirmada": false,
    "podeConfirmar": true,
    "consultaCancelada": false
  }
]
```

- `dataHora` traz o início da consulta gravado na notificação, em ISO 8601 UTC. `medicoNome` também vem dos dados gravados no momento da criação da notificação.
- `lida` indica que a leitura foi registrada. A requisição de listagem apenas consulta a central.
- `presencaConfirmada` indica que o lembrete já registrou confirmação ou que a consulta correspondente já está `CONFIRMADA` e ainda corresponde ao paciente e ao horário registrados no lembrete.
- `podeConfirmar` indica que o lembrete ainda permite a ação. A consulta deve pertencer ao paciente, continuar `AGENDADA` e conservar o mesmo início, que deve estar no futuro.
- `consultaCancelada` (#38) indica que a consulta citada foi cancelada depois que o aviso foi entregue (mesma consulta e mesmo início). O lembrete e a confirmação de agendamento continuam na central como histórico, mas a tela mostra o selo **Consulta cancelada** e não oferece confirmar presença. O próprio aviso `CANCELAMENTO_CONSULTA` vem com `false`.

## 3. POST /notificacoes/lida e POST /notificacoes/confirmar

Os dois endpoints recebem o mesmo corpo com `Content-Type: application/json`. Use o `id` devolvido na listagem como `notificacaoId` (ObjectId de 24 caracteres hexadecimais).

```json
{
  "notificacaoId": "650000000000000000000001"
}
```

- `POST /notificacoes/lida` marca a notificação como lida sem alterar a consulta. Repetir a operação retorna 204 e preserva o instante da primeira leitura.
- `POST /notificacoes/confirmar` confirma pelo lembrete entregue ao paciente. O servidor revalida titularidade, estado e horário da consulta. Na transição de `AGENDADA` para `CONFIRMADA`, o fluxo auditado registra o evento `CONFIRMACAO` no [histórico da consulta](consultas.md#5-telas-front-end). O servidor também registra a leitura e a confirmação na notificação.
- Se a notificação já registrou a confirmação, repetir a chamada retorna 204. Caso a consulta já esteja confirmada por outro caminho e continue no horário do lembrete, ainda no futuro, a operação registra a confirmação na notificação sem criar outro evento no histórico.

As duas ações retornam 204 sem corpo. A criação de lembretes também é idempotente. O índice de unicidade impede a reinserção da mesma combinação de consulta, tipo, destinatário, canal e antecedência em horas. Veja a [modelagem de notificações](../modelagem/notificacoes.md#índices).

## 4. Erros

| Status | Quando |
|---|---|
| 400 | Corpo vazio, JSON inválido ou `notificacaoId` ausente ou inválido |
| 401 | Autenticação ausente, inválida ou expirada |
| 403 | Perfil sem acesso, login sem cadastro vinculado ou consulta fora do escopo do paciente na confirmação |
| 404 | Notificação inexistente, fora da central ou de outro destinatário. Na confirmação, também pode indicar que a consulta deixou de existir |
| 409 | A notificação selecionada não é um lembrete, o horário mudou ou já chegou, ou a consulta não aceita a transição |

Um conflito ao tentar confirmar outra notificação retorna este corpo.

```json
{
  "status": 409,
  "erro": "Selecione um lembrete de consulta"
}
```

## 5. Testes

| Teste | O que cobre |
|---|---|
| `integration/RotasAutorizacaoTest` | Permissões por perfil da listagem, da leitura e da confirmação |
| `integration/FilaEsperaIntegrationTest` | Lembrete único após reprocessamento, leitura sem alterar o estado da consulta, isolamento entre pacientes e confirmação repetida com um único evento no histórico |
| `integration/NotificacoesIntegrationTest` | Geração e envio simulado, descarte de avisos pendentes e proteção contra duplicidade em novos ciclos |
| `frontend/src/__tests__/Notificacoes.test.jsx` | Leitura na central sem remover a opção de confirmar presença |
