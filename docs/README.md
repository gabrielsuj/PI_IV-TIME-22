# Documentação do ConsultaFácil

Índice de toda a documentação do projeto. Para instalar e executar, veja o [README principal](../README.md).

## Projeto (ideação, requisitos, planejamento)

| Documento | Conteúdo |
|---|---|
| [Relatório consolidado (PDF)](ConsultaFacil_Relatorio_Consolidado.pdf) | Problema, ideação e classificação das ideias, SWOT, requisitos, escopo do MVP, cronograma por sprints (5W2H), matriz de responsabilidades e cenários de aceite (Quadro 4) |

As pastas `ideacao/`, `requisitos/`, `cronograma/`, `prototipo/`, `reunioes/`, `relatorios/` e `adr/` estão reservadas para artefatos avulsos. Hoje esse conteúdo está concentrado no relatório consolidado.

## Modelagem do banco (MongoDB)

Os validadores (`$jsonSchema`) e índices ficam em [`database/init/`](../database/init); os documentos abaixo explicam cada coleção.

| Documento | Coleções |
|---|---|
| [usuarios-pacientes.md](modelagem/usuarios-pacientes.md) | `usuarios` (login, senha PBKDF2, perfil) e `pacientes` |
| [medicos-agenda.md](modelagem/medicos-agenda.md) | `especialidades`, `medicos`, `agendas` (jornada semanal e duração) e `bloqueios` |
| [consultas.md](modelagem/consultas.md) | `consultas`, status e índices únicos parciais que protegem as reservas |
| [historico.md](modelagem/historico.md) | `historico`: dicionário de dados, eventos e transições de status |
| [historico-implementacao.md](modelagem/historico-implementacao.md) | Como o back-end grava o histórico (eventos pendentes, versão, recuperação) |
| [filaEspera.md](modelagem/filaEspera.md) | `filaEspera` (fila de espera: inscrição pela API na #31, sinalização após cancelamento na #32) |
| [notificacoes.md](modelagem/notificacoes.md) | `notificacoes` (avisos de vaga, lembretes simulados e dados da central) |
| [carga-demonstracao.md](modelagem/carga-demonstracao.md) | Dados fictícios de demonstração (`database/seed/`) e o verificador de coerência |

## API REST

As rotas e os perfis autorizados estão em [Rotas.java](../backend/src/main/java/br/edu/puccampinas/consultafacil/api/Rotas.java) e são verificados em [RotasAutorizacaoTest.java](../backend/src/test/java/br/edu/puccampinas/consultafacil/integration/RotasAutorizacaoTest.java).

| Documento | Rotas |
|---|---|
| [medicos-especialidades.md](api/medicos-especialidades.md) | `/medicos`, `/especialidades` (cadastro, edição, desativação) |
| [agendamento.md](api/agendamento.md) | `GET /vagas` (disponibilidade) e `POST /consultas` (agendamento) |
| [consultas.md](api/consultas.md) | `GET /consultas/lista` e `GET /consultas/{id}` (listagem e detalhe por perfil) e as rotas de alteração de status e histórico |
| [Calendário de consultas](api/consultas.md#7-get-consultas-calendário) | `GET /consultas` com período, escopo por médico e `fusoHorario` opcional |
| [cancelamento.md](api/cancelamento.md) | `POST /consultas/{id}/cancelamento` (cancelamento por paciente, médico ou secretaria) |
| [fila-espera.md](api/fila-espera.md) | `POST/GET /fila-espera` e `DELETE /fila-espera/{id}` e `GET/POST /avisos-vaga` |
| [Indicadores](api/indicadores.md) | `GET /indicadores` com período, escopo por perfil, contagens e taxas |
| [Central de notificações](api/notificacoes.md) | `GET /notificacoes`, `POST /notificacoes/lida` e `POST /notificacoes/confirmar` |
| [alteracoes-agenda.md](api/alteracoes-agenda.md) | `GET/PUT /agendas` e `GET/POST/DELETE /bloqueios`, incluindo confirmação quando a mudança atinge consultas marcadas |

As rotas sem documento próprio são `/health`, `/auth/login`, `/auth/logout`, `/auth/me` e `/pacientes`. Consulte a [estratégia de testes](testes/estrategia-de-testes.md) para localizar os testes de integração correspondentes.

## Operação

| Documento | Conteúdo |
|---|---|
| [Entrega do MVP](operacao/entrega-mvp.md) | Demonstração por perfil, fila e lembretes, pendências de homologação e preparação da versão final |
| [backup-recuperacao.md](operacao/backup-recuperacao.md) | Backup, restauração e teste de recuperação dos bancos, com um comando cada |

## Testes

| Documento | Conteúdo |
|---|---|
| [estrategia-de-testes.md](testes/estrategia-de-testes.md) | Como os testes foram feitos, cobertura dos cenários de aceite (Quadro 4), bancos usados e comandos |
| [garantias-de-reserva.md](testes/garantias-de-reserva.md) | Como as reservas são protegidas (API + índices únicos parciais) e os testes de concorrência pela API |
