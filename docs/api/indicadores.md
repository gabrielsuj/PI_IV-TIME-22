<!--
Autoria: Gabriel Martins de Almeida
RA: 25006162
-->

# API de indicadores do painel

`GET /indicadores` devolve as contagens e taxas do período para a clínica ou para um médico. A operação é de leitura e exige `Authorization: Bearer <token>`. O painel usa esse contrato para apresentar ocupação, faltas, cancelamentos, confirmações e preenchimento pela fila (#36, #37 e #48).

## Parâmetros e acesso

| Parâmetro | Obrigatório | Valores |
|---|---|---|
| `de` | Sim | Primeiro dia do período em `AAAA-MM-DD` |
| `ate` | Sim | Último dia do período em `AAAA-MM-DD`, igual ou posterior a `de` |
| `medicoId` | Não | ID de cadastro do médico, com 24 dígitos hexadecimais. O perfil `MEDICO` também pode enviar `meu`. |

O período aceita até **62 dias corridos**, com ambas as datas incluídas. O recorte começa no início de `de` e termina no início do dia seguinte a `ate`, com o fim excluído. O fuso é `America/Sao_Paulo`. As contagens de consultas usam o instante de início da consulta para determinar sua inclusão.

- **`SECRETARIA_ADMIN`** consulta toda a clínica ao omitir `medicoId`. Para filtrar, informa o ID de cadastro do profissional.
- **`MEDICO`** consulta somente o cadastro ligado ao próprio login. Pode omitir o parâmetro, enviar `meu` ou informar seu ID de cadastro. Em todos esses casos a resposta devolve esse ID.
- **`PACIENTE`** recebe 403.

## Resposta 200

Exemplo de consulta da secretaria para um dia com dados.

```http
GET /indicadores?de=2026-11-20&ate=2026-11-20
Authorization: Bearer <token>
```

```json
{
  "de": "2026-11-20",
  "ate": "2026-11-20",
  "fusoHorario": "America/Sao_Paulo",
  "medicoId": null,
  "contagens": {
    "totalConsultas": 6,
    "consultasNaoCanceladas": 5,
    "consultasComDesfecho": 3,
    "capacidade": 8,
    "vagasOcupadas": 6,
    "agendadas": 1,
    "confirmadas": 1,
    "concluidas": 2,
    "faltas": 1,
    "canceladas": 1,
    "comConfirmacaoRegistrada": 4,
    "preenchidasPelaFila": 2
  },
  "ocupacao": {"numerador": 6, "denominador": 8, "percentual": 75.00, "situacao": "DISPONIVEL"},
  "faltas": {"numerador": 1, "denominador": 3, "percentual": 33.33, "situacao": "DISPONIVEL"},
  "cancelamento": {"numerador": 1, "denominador": 6, "percentual": 16.67, "situacao": "DISPONIVEL"},
  "confirmacao": {"numerador": 4, "denominador": 6, "percentual": 66.67, "situacao": "DISPONIVEL"},
  "preenchimentoFila": {"numerador": 2, "denominador": 5, "percentual": 40.00, "situacao": "DISPONIVEL"},
  "qualidade": {
    "medicosSemAgenda": 0,
    "consultasComProjecaoPendente": 0,
    "consultasSemBaseHistorica": 0,
    "inscricoesComVinculoInvalido": 0,
    "capacidadeEstimada": true,
    "observacaoCapacidade": "Capacidade calculada pela agenda atual; alterações históricas de jornada, duração e bloqueios não são reconstruídas."
  }
}
```

`medicoId` vem explicitamente como `null` no escopo da clínica e como ID do profissional no escopo individual. A resposta contém totais agregados, sem nomes ou IDs de pacientes.

### Contagens e taxas

`totalConsultas` é a soma de agendadas, confirmadas, concluídas, faltas e canceladas. `consultasNaoCanceladas` exclui as canceladas. `consultasComDesfecho` conta concluídas e faltas. As cinco contagens por status representam o estado atual das consultas no recorte.

| Campo | Numerador | Denominador |
|---|---|---|
| `ocupacao` | Vagas da grade que se sobrepõem a pelo menos uma consulta não cancelada | Vagas da jornada atual com início no período, respeitando duração e bloqueios |
| `faltas` | Consultas com status `FALTA` | Consultas concluídas ou com falta |
| `cancelamento` | Consultas com status `CANCELADA` | Total de consultas |
| `confirmacao` | Consultas distintas com evento projetado `CONFIRMACAO` no histórico | Total de consultas |
| `preenchimentoFila` | Consultas não canceladas vinculadas pelo campo `consultaAgendadaId` a uma inscrição `ATENDIDA`, contando cada consulta uma vez | Consultas não canceladas |

Uma consulta pode ocupar mais de uma vaga da grade. Cada vaga conta uma vez na ocupação, inclusive se a consulta sobreposta começou antes do período. A taxa de confirmação usa o evento histórico e inclui consultas que depois foram concluídas ou canceladas. Para essa taxa, o intervalo considera a data de início da consulta. [Cálculo e disponibilidade de agenda](agendamento.md) · [Histórico das consultas](../dicionario/historico.md) · [API de fila de espera](fila-espera.md)

### Qualidade dos dados

Cada taxa tem `numerador`, `denominador`, `percentual` e `situacao`. Quando disponível, o percentual é um número de 0 a 100, arredondado a duas casas decimais. Nos outros estados, `percentual` vem explicitamente como `null`.

| Situação | Quando ocorre |
|---|---|
| `DISPONIVEL` | Há denominador positivo e os dados necessários estão completos |
| `SEM_BASE` | O denominador é zero e não há pendência ou lacuna na fonte da taxa |
| `DADOS_PENDENTES` | Uma ou mais consultas do recorte têm projeção de histórico pendente. A taxa de confirmação fica indefinida até a atualização. |
| `DADOS_INCOMPLETOS` | Consulta sem base histórica suficiente para medir confirmação, médico com consulta no recorte e sem agenda para medir ocupação, ou inscrição atendida com vínculo inválido na fonte usada para medir preenchimento da fila |

`qualidade` informa as contagens dessas ocorrências. Na confirmação, a projeção pendente tem precedência sobre a base histórica incompleta. Uma situação indefinida afeta só a taxa correspondente. As contagens continuam presentes para diagnóstico. A capacidade usa sempre a agenda atual, conforme `capacidadeEstimada` e `observacaoCapacidade`. Um médico sem agenda e sem consultas no período não torna a ocupação da clínica incompleta.

## Erros

| HTTP | Quando |
|---|---|
| 400 | Datas ausentes, inválidas, invertidas ou fora do limite de 62 dias, `medicoId` inválido ou parâmetros desconhecidos, repetidos ou vazios |
| 401 | Token ausente, inválido, expirado ou encerrado |
| 403 | Perfil paciente, médico sem vínculo ou médico tentando consultar outro cadastro |
| 404 | Médico solicitado com ID válido e sem cadastro, dentro do escopo autorizado |
| 503 | MongoDB ou coleções indisponíveis, fontes inconsistentes ou dados que mudaram durante a leitura |

Uma leitura sem consultas no período devolve 200. A ocupação pode ser 0% quando a agenda oferece vagas. A interface usa `situacao` para distinguir uma taxa indisponível de um zero calculado. O 503 informa que a leitura falhou e permite tentar novamente.

## Testes

`IndicadoresHttpTest` verifica parâmetros, permissões, resposta, fórmulas e estados de qualidade. `IndicadoresMongoIntegrationTest` confere a composição com o MongoDB. `IndicadoresDemonstracaoIntegrationTest` compara as contagens e taxas da clínica e dos médicos com os dados carregados para demonstração. [Estratégia de testes](../testes/estrategia-de-testes.md)
