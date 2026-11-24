<!--
Autoria: Murilo Moraes
RA: 25000073
-->

# Configuração e variáveis de ambiente

> Issue #50. Reúne, num lugar só, os processos do sistema, as portas, todas as variáveis lidas pelo código (com o valor usado quando a variável não existe) e as variáveis que só os testes usam. Para instalar e executar passo a passo, veja o [README principal](../../README.md).

## 1. Processos e portas

O ConsultaFácil roda em quatro processos. Na mesma máquina, cada um precisa de uma porta própria.

| Processo | Como iniciar | Porta recomendada | Configurada por |
|---|---|---|---|
| MongoDB 7 | `docker compose up -d mongo` | `27017` (só em `127.0.0.1`) | `docker-compose.yml` |
| Servidor TCP de CPF (`server/`) | `java -cp server/target/classes br.edu.puccampinas.consultafacil.Servidor` | `3001` | argumento da linha de comando (padrão `3001`) |
| API (`backend/`) | `./mvnw compile exec:java` na pasta `backend` | `3000` | `SERVER_PORT` |
| Front-end (`frontend/`) | `npm run dev` na pasta `frontend` | `5173` | Vite |

```
navegador ──HTTP──▶ front (5173) ──HTTP/JSON──▶ API (3000) ──▶ MongoDB (27017)
                                                     └──TCP──▶ servidor de CPF (3001)
```

> **Portas 3000 e 3001.** Até a #38, a API e o servidor de CPF usavam a 3000 por padrão e não conseguiam rodar juntos sem ajuste manual. Agora o servidor de CPF, o cliente TCP, o padrão da API (`CPF_SERVER_PORT`), o `.env.example`, os testes e o CI usam **3001**. Se o seu `.env` é antigo e não tem `CPF_SERVER_PORT`, a API já usa 3001; se tiver `CPF_SERVER_PORT=3000`, troque para 3001.

## 2. Onde o `.env` é lido

Há um único `.env`, na **raiz do repositório**, criado a partir do `.env.example`. Ele nunca é versionado.

- **API:** `AppConfig.load()` usa o dotenv com `directory("..")`. Como a API roda a partir de `backend/`, ela lê `../.env`. Uma variável de ambiente do sistema tem prioridade sobre o arquivo.
- **Front-end:** o `vite.config.js` usa `envDir: '..'`, então lê o mesmo `.env`. Só as variáveis com prefixo `VITE_` chegam ao navegador, e o valor fica gravado no momento do `npm run build`.
- **Servidor de CPF:** não lê o `.env`, só a porta passada como argumento.

## 3. Variáveis da aplicação

"Padrão no código" é o valor usado quando a variável não está nem no ambiente nem no `.env`.

| Variável | Lida por | Padrão no código | `.env.example` | Para que serve |
|---|---|---|---|---|
| `MONGODB_URI` | API | `mongodb://localhost:27017` | `mongodb://localhost:27017/?serverSelectionTimeoutMS=5000` | Conexão com o MongoDB. O timeout curto faz a API falhar rápido se o banco estiver fora do ar |
| `MONGODB_DATABASE` | API | `consultafacil` | `consultafacil` | Banco usado: `consultafacil` (desenvolvimento) ou `consultafacil_demo` (apresentação) |
| `SERVER_PORT` | API | `8080` | `3000` | Porta HTTP da API |
| `CORS_ALLOWED_ORIGINS` | API | `http://localhost:5173` | `http://localhost:5173` | Origens do front aceitas, separadas por vírgula |
| `SESSION_TTL_MINUTES` | API | `60` | `60` | Validade do token de login, em minutos. Depois disso a API responde `401` e o front volta ao login |
| `NOTIFICACOES_ANTECEDENCIA_HORAS` | API | `24` | `24` | Antecedência do lembrete de consulta (#34) |
| `NOTIFICACOES_INTERVALO_SEGUNDOS` | API | `60` | `60` | Intervalo do agendador de notificações. Precisa ser ≥ 1 |
| `NOTIFICACOES_EMAIL_TESTE` | API | `false` | `false` | `true` grava também uma cópia "enviada" por e-mail de teste no log |
| `CPF_SERVER_HOST` | API | `localhost` | `localhost` | Endereço do servidor TCP de CPF |
| `CPF_SERVER_PORT` | API | `3001` | `3001` | Porta do servidor de CPF. Precisa ser a mesma em que o servidor foi iniciado |
| `VITE_API_BASE_URL` | Front-end | `http://localhost:8080` | `http://localhost:3000` | URL da API chamada pelo navegador. Deve apontar para a porta de `SERVER_PORT` |

Os padrões da API (`8080`) e do front (`http://localhost:8080`) são compatíveis entre si. Se você remover `SERVER_PORT`, remova também `VITE_API_BASE_URL`, ou ajuste as duas juntas.

## 4. Configuração dos testes

Os testes não leem o `.env`. Eles usam bancos e portas fixos, e algumas suítes só rodam quando uma variável é definida.

| Item | Valor | Quem usa |
|---|---|---|
| Banco de testes | `consultafacil_test` em `localhost:27017` | Testes de integração com MongoDB, entre eles `AcessoPacientesMongoIntegrationTest` (#50) e `FilaEsperaIntegrationTest` |
| `HISTORICO_MONGO_URI` | ex.: `mongodb://localhost:27017/?serverSelectionTimeoutMS=3000` | Liga `HistoricoPersistenceIntegrationTest` e `IndicadoresMongoIntegrationTest` (banco `consultafacil_historico_test`). Sem ela, esses testes são pulados |
| `DEMO_MONGO_DB` | `consultafacil_demo` | Liga `IndicadoresDemonstracaoIntegrationTest`, que confere a carga de demonstração. Sem ela, o teste é pulado |
| `-DmongoTests=true` | propriedade do Maven | Liga `CadastroMongoIntegrationTest` |
| Servidor de CPF | `localhost:3001` (iniciado sem argumento) | `ValidadorCpfTcpTest` e o caso de dígito verificador do `AcessoPacientesMongoIntegrationTest` |
| Módulo `server` instalado | `cd server && ../backend/mvnw install` | Dependência de compilação do backend desde a integração do validador de CPF |

Os bancos de teste precisam dos validadores e índices de `database/init/`. O passo a passo, igual ao CI, está em [estrategia-de-testes.md](../testes/estrategia-de-testes.md#como-executar).

## 5. Acesso e permissões

- **Login:** `POST /auth/login` devolve um token válido por `SESSION_TTL_MINUTES`. O front guarda o token na sessão do navegador e o envia em `Authorization: Bearer <token>`. Contrato completo em [autenticacao.md](../api/autenticacao.md).
- **Perfis:** `PACIENTE`, `MEDICO` e `SECRETARIA_ADMIN`, gravados em `usuarios.perfil`. Um usuário com `ativo: false` não entra, mesmo com a senha correta.
- **Matriz de permissões:** a tabela de quem pode cada operação está em [estrategia-de-testes.md, "Matriz de permissões"](../testes/estrategia-de-testes.md#matriz-de-permissões). A fonte é `api/Rotas.java`, verificada rota a rota em `RotasAutorizacaoTest` e com login real no MongoDB em `AcessoPacientesMongoIntegrationTest`.
- **Pacientes:** só a secretaria cadastra e edita, o médico só lê e o paciente não acessa o cadastro. Contrato em [pacientes.md](../api/pacientes.md).

## 6. Problemas comuns

| Sintoma | Causa provável | Como resolver |
|---|---|---|
| `Address already in use` ao iniciar a API ou o servidor de CPF | Outro processo (ou uma cópia antiga) já ocupa a porta; ou o servidor de CPF foi iniciado com `3000` | Encerre o processo antigo; inicie o servidor de CPF sem argumento (3001) e confira `CPF_SERVER_PORT` |
| Salvar paciente retorna `503` "Serviço de validação de CPF temporariamente indisponível" | Servidor de CPF parado, ou `CPF_SERVER_PORT` diferente da porta em que ele está rodando | Inicie o servidor e confira a porta |
| O front mostra "API indisponível" e o console aponta `localhost:8080` | `VITE_API_BASE_URL` ausente no `.env` | Defina `VITE_API_BASE_URL=http://localhost:3000` e reinicie o `npm run dev` |
| Erro de CORS no navegador | O front está em uma origem fora de `CORS_ALLOWED_ORIGINS` | Inclua a origem (ex.: `http://127.0.0.1:5173`) na variável, separada por vírgula |
| Depois de um tempo, toda ação volta para o login | O token passou de `SESSION_TTL_MINUTES` | Comportamento esperado: entre de novo. Os formulários de pacientes recuperam o rascunho após o novo login |
| Backend não compila: `Could not find artifact ...:server:jar` | O módulo `server` não foi instalado no Maven local | `cd server && ../backend/mvnw install -DskipTests` |
| `UnsupportedClassVersionError ... class file version 71.0` ao iniciar o servidor de CPF | A extensão Java do VS Code recompilou `server/target/classes` com um JDK mais novo, e o Maven não recompilou porque a classe parecia atualizada | `cd server && ../backend/mvnw clean install -DskipTests` |
| Testes de integração falham com erro de schema ou de índice | `consultafacil_test` sem os scripts de `database/init/` | Recrie o banco de testes como em [estrategia-de-testes.md](../testes/estrategia-de-testes.md#como-executar) |

## 7. Observações sobre execução e implantação

- **Health:** a página inicial e a tela **Status do sistema** (`/status`) consultam o `GET /health` da API. Esse endpoint indica se o servidor HTTP responde, mas não faz um novo ping no banco nem consulta o serviço TCP de CPF.
- **Uma instância da API:** as travas de intervalos coordenam uma única instância. Os índices únicos do MongoDB impedem reservas ativas com início igual, mas não impedem, sozinhos, sobreposições com inícios diferentes entre instâncias. Para implantação, mantenha uma instância.
- **Build do frontend:** o `npm run build` incorpora `VITE_API_BASE_URL` no momento do build. Se a URL da API mudar, gere o build de novo. Para usar o `npm run preview` (`http://localhost:4173`), inclua essa origem em `CORS_ALLOWED_ORIGINS` e reinicie a API.
- **Cliente TCP de CPF:** o cliente didático não impõe timeout automático. A espera pela resposta ou pelo cabeçalho do stream pode continuar enquanto o outro lado mantiver a conexão aberta e silenciosa. Mudar essa política exige uma decisão específica sobre o requisito.
