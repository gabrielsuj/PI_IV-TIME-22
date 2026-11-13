# API de médicos e especialidades

> Issue #13 (cadastro de médicos e especialidades). Modelagem: [medicos-agenda.md](../modelagem/medicos-agenda.md)
> (validadores em `database/init/06-esquema-especialidades.js` e `07-esquema-medicos.js`).

## Rotas e permissões

Todas as chamadas exigem `Authorization: Bearer <token>` da sessão existente.

| Método | Caminho | Resultado | Perfis autorizados |
|---|---|---|---|
| GET | `/medicos` | 200, array de médicos | PACIENTE, MEDICO, SECRETARIA_ADMIN |
| GET | `/medicos/{id}` | 200, médico | PACIENTE, MEDICO, SECRETARIA_ADMIN |
| POST | `/medicos` | 201, médico e header Location | SECRETARIA_ADMIN |
| PUT | `/medicos/{id}` | 200, médico atualizado | SECRETARIA_ADMIN |
| GET | `/especialidades` | 200, array de especialidades | PACIENTE, MEDICO, SECRETARIA_ADMIN |
| GET | `/especialidades/{id}` | 200, especialidade | PACIENTE, MEDICO, SECRETARIA_ADMIN |
| POST | `/especialidades` | 201, especialidade e header Location | SECRETARIA_ADMIN |
| PUT | `/especialidades/{id}` | 200, especialidade atualizada | SECRETARIA_ADMIN |

Listas incluem ativos e inativos, ordenados por nome e ID, sem paginação/filtros.
Lista vazia retorna `[]`. A leitura pelo paciente é usada na busca de vagas
e na fila de espera; o catálogo não representa vagas disponíveis. `usuarioId` não é
exposto nem editável por estes endpoints. IDs são strings hexadecimais de 24
caracteres na API e ObjectId no banco, inclusive em `especialidadeIds`.

Os contextos HTTP são registrados em `App` e protegidos por `Rotas.catalogo`,
incluindo caminhos com ID, independentemente do Router de correspondência exata.
OPTIONS mantém o preflight de CORS existente sem exigir autenticação.
Aceita-se também a barra final na rota de coleção. Não há DELETE; PUT exige o
objeto completo com todos os campos editáveis, inclusive o status booleano.

## Objetos de entrada e saída

POST e PUT de especialidade:

```json
{"codigo":"cardiologia","nome":"Cardiologia","ativa":true}
```

Resposta:

```json
{"id":"000000000000000000000002","codigo":"cardiologia","nome":"Cardiologia","ativa":true}
```

POST e PUT de médico:

```json
{"nome":"Ana Teste","crmNumero":"000123","crmUf":"SP","especialidadeIds":["000000000000000000000002"],"ativo":true}
```

Resposta:

```json
{"id":"100000000000000000000001","nome":"Ana Teste","crmNumero":"000123","crmUf":"SP","especialidadeIds":["000000000000000000000002"],"ativo":true}
```

Os IDs dos exemplos são ilustrativos: primeiro cadastre a especialidade e use
seu `id` real ao cadastrar o médico.

## Validações e erros

- Nome: obrigatório, sem espaços nas extremidades, 3 a 120 caracteres para médico
  e 1 a 100 para especialidade, conforme os schemas.
- CRM: texto com 1 a 10 dígitos, preservando zeros; UF: uma das 27 siglas
  brasileiras, em maiúsculas. O par UF + número é único, mesmo para inativos.
- Código: texto obrigatório com padrão `^[a-z0-9-]+$`, único mesmo para inativas.
  Renomear uma especialidade não altera seu código automaticamente.
- `ativo` e `ativa`: booleanos JSON obrigatórios, sem coerção de strings/números.
- `especialidadeIds`: lista obrigatória, não vazia, sem repetição (inclusive IDs
  equivalentes em maiúsculas/minúsculas). Cada referência precisa existir e estar
  ativa em todo cadastro/edição de médico, inclusive ao reativá-lo.
- Apenas os campos acima são aceitos. `descricao`, `_id`, `id` e `usuarioId` no
  corpo retornam 400, com o nome do campo não permitido. A API monta documentos
  explicitamente e atualiza via `$set`, preservando vínculos existentes de login.
- Corpo: um único objeto JSON, máximo de 16 KiB.

| Código HTTP | Situação |
|---|---|
| 400 | JSON, tipos, campos, IDs, formatos, tamanho ou referências inválidos |
| 401 | Sessão ausente, inválida ou expirada |
| 403 | Perfil sem permissão de manutenção |
| 404 | Rota inexistente ou ID válido sem registro |
| 405 | Método não suportado; header Allow informa os permitidos |
| 409 | CRM/código duplicado ou desativação de especialidade com médicos ativos |
| 500 | Falha interna, sem expor detalhes do banco |

Erros usam `Http.sendError`: `{"status":400,"erro":"mensagem"}`. A unicidade é
garantida pelos índices existentes (`uk_medico_crm`, `uk_especialidade_codigo`),
inclusive sob concorrência. O repositório traduz o erro 11000 em 409 e o 121 em
400. É necessário executar a API com a modelagem instalada; estes endpoints não
criam nem recriam o banco.

## Política de desativação e referências

Médico é desativado com PUT e `ativo:false`; mantém ID, especialidades, login,
agendas, bloqueios, consultas e históricos. `MedicoRepository.existeAtivo()` foi
preservado para os serviços de agenda/bloqueios.

Especialidade é desativada com PUT e `ativa:false` somente quando nenhum médico
ativo a referencia. Se houver vínculo ativo, retorna 409 sem alterar registros:
primeiro desvincule a especialidade desses médicos (mantendo pelo menos uma outra
especialidade ativa) ou desative os médicos. Vínculos de médicos inativos são
preservados. Para reativar/editar um médico depois, reative suas especialidades ou
substitua-as por especialidades ativas. Não há remoção em cascata.

A verificação de referências fica no serviço, pois o MongoDB não oferece chave
estrangeira entre coleções. Ela considera o estado lido antes da gravação; não há
transação entre coleções no Mongo standalone. Escritas externas ou múltiplas
instâncias simultâneas exigem coordenação adicional para essa política.

O schema de especialidades não aceita `descricao`. O campo foi retirado do
formulário existente e da busca; a API recusa explicitamente payloads com esse
campo. Nenhum schema compartilhado foi ampliado e nenhum texto enviado é
silenciosamente descartado.

## Verificação

Na pasta `backend`, com Java 21 ou compilação `--release 21`:

```sh
./mvnw test -Dtest=CadastroServiceTest,CadastroHandlerTest,RotasAutorizacaoTest
```

`CadastroServiceTest` cobre regras sem banco. `CadastroHandlerTest` usa servidor
HTTP, sessão e autorização reais com repositórios de teste em memória.

Com MongoDB local e schemas/índices já instalados **somente em consultafacil_test**:

```sh
./mvnw test -DmongoTests=true -Dtest=CadastroMongoIntegrationTest
```

O teste Mongo é opt-in, verifica schemas/índices antes de gravar e limpa somente
os IDs criados por ele. Verifica persistência, preservação de `usuarioId`,
desativação, duplicidades na edição e duas criações concorrentes do mesmo CRM.
Não executa drop, recriação de banco, remoção de volumes nem limpeza global.

Na pasta `frontend`: `npm test`, `npm run lint` e `npm run build`.

## Arquivos da entrega

| Arquivo (relativo ao módulo) | Finalidade |
|---|---|
| Backend: `App.java`, `api/Rotas.java` | Registrar contextos com ID e autorização/CORS dos catálogos |
| `api/dto/MedicoRequest.java`, `MedicoResponse.java` | Contrato tipado do médico com IDs explícitos |
| `api/dto/EspecialidadeRequest.java`, `EspecialidadeResponse.java` | Contrato tipado da especialidade |
| `api/handler/CadastroHandler.java` | GET/POST/PUT, leitura estrita de JSON, status HTTP e Location |
| `repository/MedicoRepository.java`, `MedicoCadastroRepository.java` | Persistência do cadastro e consulta de vínculos ativos, preservando existeAtivo |
| `repository/EspecialidadeRepository.java` | Persistência das especialidades com tradução de erros Mongo |
| `service/MedicoService.java`, `EspecialidadeService.java`, `CadastroValidator.java` | Validações e política de referências/desativação |
| Testes: `unit/CadastroServiceTest.java` | Regras e preservação de vínculos |
| `integration/CadastroHandlerTest.java` | Contratos HTTP, tipos JSON, sessão, perfis e CORS |
| `integration/CadastroMongoIntegrationTest.java` | Verificação opt-in de persistência, índices e concorrência |
| `support/CadastrosEmMemoria.java` | Repositórios isolados para testes sem banco |
| Frontend: `src/data/registrationSchemas.js`, `src/pages/RegistrationPage.jsx` | Retirar descricao; buscar/exibir código conforme o schema atual |
| `src/__tests__/Registration.test.jsx` | Garantir envio apenas dos campos aceitos da especialidade |
| `docs/api/medicos-especialidades.md` | Contrato, decisões, comandos e resultados desta entrega |
