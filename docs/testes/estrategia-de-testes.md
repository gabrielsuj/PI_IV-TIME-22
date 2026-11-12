# Estratégia de testes

Resultado em 12/11/2026: **211 testes JUnit** no back-end e **181 testes Vitest** no front-end, todos passando.

## Como os testes são organizados

| Camada | Ferramenta | Onde | O que verifica |
|---|---|---|---|
| Regras de negócio isoladas | JUnit 5 | `backend/src/test/.../unit` | Validações, cálculos e segurança sem banco nem servidor |
| API e banco | JUnit 5 + MongoDB real | `backend/src/test/.../integration` | Serviços, repositórios, índices e respostas HTTP com um MongoDB de testes |
| Permissões | JUnit 5 + servidor HTTP real | `RotasAutorizacaoTest` | Matriz de rotas × perfis (200, 401, 403) usando a tabela real de `Rotas.java` |
| Interface | Vitest + Testing Library (jsdom) | `frontend/src/__tests__` | Telas, navegação, sessão, mensagens de erro e chamadas à API (simulada) |
| Dados de demonstração | Script mongosh | `scripts/verificar-carga.js` | Coerência da carga: jornadas, bloqueios, sobreposições, histórico e logins |

Os testes de integração usam bancos exclusivos, que são limpos a cada teste e nunca tocam o banco de desenvolvimento ou de demonstração:

| Banco | Usado por |
|---|---|
| `consultafacil_test` | Todos os testes de integração |
| `consultafacil_historico_test` | `HistoricoPersistenceIntegrationTest` (opcional) |

## Cenários de aceite do relatório (Quadro 4)

| Cenário | Teste | Situação |
|---|---|---|
| Dois usuários escolhem a mesma vaga: só um agendamento é confirmado | `ConsultaReservaIntegrationTest.deveConfirmarApenasUmAgendamentoParaMesmaVaga` (gravações simultâneas no banco) | ✅ |
| Paciente ou médico já ocupado: a sobreposição é recusada | `ConsultaReservaIntegrationTest.deveRecusarMesmoPacienteNoMesmoHorario`; `AgendamentoIntegrationTest` (horário já reservado sai da busca) | ✅ parcial. Mesmo paciente em horários **sobrepostos com início diferente** em outro médico ainda não é recusado (#25) |
| Horário bloqueado ou fora da agenda não aparece nem pode ser reservado | `AgendamentoIntegrationTest.deveImpedirReservaEmPeriodoBloqueado`, `deveRecusarHorarioForaDaJornada`, `deveRecusarHorarioQueJaPassou`; `CalculadoraDisponibilidadeTest` | ✅ |
| Cancelamento libera a vaga, atualiza o histórico e sinaliza a fila | `ConsultaReservaIntegrationTest.devePermitirReservarVagaDeConsultaCancelada`; `HistoricoTest` | ⏳ parcial. A rota de cancelamento (#27) e a fila (#31/#32) ainda não existem |
| Acesso a função de outro perfil é bloqueado pelo servidor | `RotasAutorizacaoTest.deveBloquearAcessoDePerfilNaoAutorizado` e a matriz de permissões | ✅ |
| Formulário inválido ou API indisponível: a interface informa sem perder o contexto | `UxPacientes.test.jsx`, `Registration.test.jsx`, `App.test.jsx` (API fora do ar), `AgendaConflitos.test.jsx` | ✅ |

## O que cada grupo de testes cobre

### Back-end: unidade (`unit/`)

| Classe | Cobertura |
|---|---|
| `SenhaHasherTest`, `SessoesTest` | Hash PBKDF2 com salt, sessão com expiração e logout |
| `PacienteValidatorTest`, `PacienteServiceTest` | Campos obrigatórios, CPF e e-mail, unicidade |
| `CadastroServiceTest` | Regras de médicos e especialidades (vínculo, desativação) |
| `ValidadorAgendaTest` | Jornada semanal: faixas sobrepostas, dias repetidos, duração e fuso |
| `CalculadoraDisponibilidadeTest` | Vagas a partir da jornada, descontando bloqueios, consultas e horários passados |
| `HistoricoTest` | Transições de status e eventos de histórico por perfil |

### Back-end: integração (`integration/`, requer MongoDB)

| Classe | Cobertura |
|---|---|
| `MongoConnectionIntegrationTest` | Conexão com o banco de testes |
| `AuthHandlerTest` | Login, mensagens de recusa, usuário inativo, token expirado e logout pela API |
| `RotasAutorizacaoTest` | Matriz de permissões de todas as rotas (sem banco) |
| `PacienteHandlerTest`, `CadastroHandlerTest` | CRUD de pacientes, médicos e especialidades pela API |
| `CadastroMongoIntegrationTest` | Validadores e índices reais de médicos e especialidades (opcional) |
| `AgendaBloqueioIntegrationTest` | Agenda semanal e bloqueios: gravação, sobreposição, fuso |
| `ConsultaReservaIntegrationTest` | Índices de reserva: vaga única sob concorrência, mesmo paciente, consulta cancelada |
| `AgendamentoIntegrationTest` | Busca de vagas e agendamento: bloqueio, fora da jornada, passado, perfis |
| `ConsultaCalendarioIntegrationTest` | Leitura das consultas do calendário: período, fuso, nomes, validações |
| `ConflitosAgendaIntegrationTest` | Bloqueio e mudança de jornada sobre consultas marcadas: 409 com lista e confirmação |
| `HistoricoPersistenceIntegrationTest` | Gravação do histórico com o validador real (opcional) |

### Front-end (`frontend/src/__tests__`)

| Arquivos | Cobertura |
|---|---|
| `api.test.js` | Cliente HTTP: URL base, token, erros, timeout e sessão expirada |
| `Auth.test.jsx`, `App.test.jsx` | Rotas protegidas, login e retorno à página pedida, logout, menu por perfil, status da API |
| `Registration.test.jsx`, `UxPacientes.test.jsx`, `rascunhoPacientes.test.js` | Cadastros, validação por campo, rascunho preservado após erro |
| `Agenda.test.jsx`, `AgendaConflitos.test.jsx` | Jornada semanal, bloqueios e aviso de consultas afetadas |
| `Calendario.test.jsx` | Regras de exibição (livre, ocupado, remarcar, bloqueado, indisponível), navegação e visão do dia |
| `NovaConsulta.test.jsx`, `FluxoAgendamento.test.jsx`, `FiltrosAgendamento.test.jsx`, `HorariosDisponiveis.test.jsx`, `ResumoAgendamento.test.jsx` e serviços de agendamento | Fluxo de agendamento: filtros, paciente, horários, resumo, confirmação e resultado incerto |

## Como executar

```bash
docker compose up -d mongo
bash scripts/recriar-banco.sh consultafacil_test     # validadores e índices, sem dados de exemplo

cd backend && ./mvnw test                            # unidade + integração
cd frontend && npm test                              # Vitest
cd frontend && npm run lint                          # ESLint
```

Os dois testes opcionais exigem a ativação explícita, porque usam o validador real do banco:

```bash
# banco exclusivo do teste de histórico (o recriar-banco.sh não aceita esse nome)
for f in database/init/*.js; do docker exec -i consultafacil-mongo mongosh consultafacil_historico_test --quiet < "$f"; done

cd backend
HISTORICO_MONGO_URI="mongodb://localhost:27017/?serverSelectionTimeoutMS=3000" \
  ./mvnw test -DmongoTests=true
```

Conferência da carga de demonstração:

```bash
bash scripts/recriar-banco.sh consultafacil_demo
docker exec -i consultafacil-mongo mongosh consultafacil_demo --quiet < scripts/verificar-carga.js
```

## Pendências conhecidas

- **#25**: recusar sobreposição do mesmo paciente em horários com início diferente (hoje só o mesmo início é barrado pelo índice).
- **#27**: rota de cancelamento. O cancelamento já é coberto no serviço de histórico, mas ainda não pela API.
- **#55**: testes de concorrência pela API (`POST /consultas` simultâneos) e documento de garantias de reserva.
- **#42**: o médico ainda pode consultar e alterar a agenda de outros médicos (o perfil é verificado, o dono do registro não).
