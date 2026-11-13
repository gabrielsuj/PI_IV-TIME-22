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
| [filaEspera.md](modelagem/filaEspera.md) | `filaEspera` (fila de espera, ainda não implementada na API) |
| [notificacoes.md](modelagem/notificacoes.md) | `notificacoes` (lembretes simulados, ainda não implementados na API) |
| [carga-demonstracao.md](modelagem/carga-demonstracao.md) | Dados fictícios de demonstração (`database/seed/`) e o verificador de coerência |

## API REST

Todas as rotas, com os perfis autorizados, estão resumidas em `backend/.../api/Rotas.java` e testadas em `RotasAutorizacaoTest`.

| Documento | Rotas |
|---|---|
| [medicos-especialidades.md](api/medicos-especialidades.md) | `/medicos`, `/especialidades` (cadastro, edição, desativação) |
| [agendamento.md](api/agendamento.md) | `GET /vagas` (disponibilidade) e `POST /consultas` (agendamento) |
| [cancelamento.md](api/cancelamento.md) | `POST /consultas/{id}/cancelamento` (cancelamento por paciente, médico ou secretaria) |
| [alteracoes-agenda.md](api/alteracoes-agenda.md) | `PUT /agendas` e `POST /bloqueios` quando atingem consultas marcadas (409 + `?confirmar=true`) |

Rotas sem documento próprio: `/health`, `/auth/login`, `/auth/logout`, `/auth/me`, `/pacientes`, `GET /agendas`, `GET/DELETE /bloqueios` e `GET /consultas` (calendário). O formato de cada uma está nos testes de integração correspondentes ([testes](testes/estrategia-de-testes.md)).

## Operação

| Documento | Conteúdo |
|---|---|
| [backup-recuperacao.md](operacao/backup-recuperacao.md) | Backup, restauração e teste de recuperação dos bancos, com um comando cada |

## Testes

| Documento | Conteúdo |
|---|---|
| [estrategia-de-testes.md](testes/estrategia-de-testes.md) | Como os testes foram feitos, cobertura dos cenários de aceite (Quadro 4), bancos usados e comandos |
| [garantias-de-reserva.md](testes/garantias-de-reserva.md) | Como as reservas são protegidas (API + índices únicos parciais) e os testes de concorrência pela API |
