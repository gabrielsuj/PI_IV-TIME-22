# ConsultaFácil

> Sistema web de agendamento de consultas médicas com fila de espera automática.

![Status](https://img.shields.io/badge/status-em%20desenvolvimento-yellow)
![Java](https://img.shields.io/badge/Java-21-orange)
![React](https://img.shields.io/badge/React-JavaScript-61DAFB)
![MongoDB](https://img.shields.io/badge/MongoDB-7-47A248)
![Licença](https://img.shields.io/badge/licen%C3%A7a-a%20definir-lightgrey)

## Sumário

- [Sobre o projeto](#sobre-o-projeto)
- [Tecnologias utilizadas](#tecnologias-utilizadas)
- [Arquitetura da aplicação](#arquitetura-da-aplicação)
- [Pré-requisitos e guia inicial de execução](#pré-requisitos-e-guia-inicial-de-execução)
  - [Início rápido](#início-rápido)
- [Roteiro de entrega e demonstração](docs/operacao/entrega-mvp.md)
- [Documentação](#documentação)
- [Estrutura de pastas](#estrutura-de-pastas)
- [Informações acadêmicas e autoria](#informações-acadêmicas-e-autoria)

---

## Sobre o projeto

### O problema

Cancelamentos e faltas deixam horários vagos na agenda de clínicas e consultórios. O preenchimento dessas vagas depende de localizar pacientes interessados e disponíveis, tarefa geralmente feita por contatos manuais, com controles dispersos entre papel, planilhas e mensagens. O resultado é retrabalho para a secretaria, horários ociosos e perda de oportunidades de atendimento.

A literatura indica a dimensão do problema: uma revisão sistemática de 105 estudos identificou taxa média de não comparecimento de 23% em agendamentos de saúde (Dantas et al., 2018), e estudos brasileiros registram absenteísmo de 19,2% em uma unidade básica de saúde de Pelotas (RS) e de 38,6% em consultas especializadas no Espírito Santo. Notificações digitais, por sua vez, aumentam o comparecimento (Robotham et al., 2016).

### A solução

O **ConsultaFácil** centraliza a agenda, valida conflitos de horário e conecta cada cancelamento a uma fila de espera, sinalizando a vaga liberada aos pacientes interessados.

**Persona principal:** a secretária, recepcionista ou responsável pela gestão da agenda.
**Clientes contratantes:** clínicas, consultórios e profissionais de saúde.
**Usuários:** secretaria/administrador, médicos e pacientes.

### Objetivos

- Centralizar cadastros, agenda e consultas em um único fluxo.
- Impedir conflitos de reserva (duas confirmações para a mesma vaga, sobreposições, períodos bloqueados ou fora da jornada).
- Conectar o cancelamento à fila de espera, sinalizando vagas compatíveis.
- Melhorar o aproveitamento da agenda e acompanhar faltas, cancelamentos e confirmações.

### Escopo do MVP

| Funcionalidade | Descrição |
|---|---|
| Acesso | Login e autorização por perfil (Paciente, Médico, Secretaria/Administrador) |
| Cadastros | Pacientes, médicos e especialidades |
| Agenda | Dias, horários, duração das consultas e bloqueios |
| Consultas | Busca de vagas, agendamento, cancelamento e alteração de status |
| Conflitos | Bloqueio de reservas duplicadas e sobrepostas |
| Fila de espera | Registro de interesse e sinalização de vaga após cancelamento |
| Lembretes | Notificações simuladas no sistema ou e-mail de teste |
| Painel | Indicadores, agenda do dia e próximas consultas |
| Histórico | Registro de criação, confirmação, cancelamento, conclusão e falta |

**Fora do MVP:** WhatsApp e SMS reais, teleconsulta, prontuário eletrônico, pagamentos e convênios, relatórios avançados, previsão de faltas por inteligência artificial e aplicativo móvel.

### Situação atual (20/11/2026)

| Funcionalidade | Situação |
|---|---|
| Login, sessão com expiração, logout e menu por perfil | ✅ |
| Cadastro, busca e detalhe do paciente, com edição pela secretaria e leitura pelo médico | ✅ #14 |
| Cadastros de médicos e especialidades | ✅ |
| Agenda: jornada semanal, duração e bloqueios, com aviso quando a mudança atinge consultas marcadas | ✅ |
| Calendário operacional por médico (semana e dia) | ✅ |
| Busca de vagas e agendamento pela secretaria, com escolha de outro horário após conflito | ✅ #54 |
| Registro e consulta do histórico no detalhe da consulta | ✅ #29, #46 |
| Listagem, detalhe e cancelamento de consultas | ✅ |
| Fila de espera na API: inscrição, sinalização da vaga após cancelamento e atualização quando a vaga é ocupada | ✅ #31, #32 |
| Avisos de vaga para leitura pelo paciente, com agendamento pela secretaria e expiração | ✅ #57 |
| Telas da fila de espera (paciente e secretaria) | ✅ #33 |
| Confirmação, conclusão e falta pela interface | ✅ #28 |
| Proteção contra consultas sobrepostas do mesmo paciente em médicos diferentes | ✅ #25 |
| Central de notificações, leitura e confirmação de presença pelo lembrete | ✅ #35 |
| Confirmação de agendamento, aviso de cancelamento e lembretes com envio periódico (e-mail de teste opcional) | ✅ #34 |
| API de indicadores, conferida com a carga de demonstração | ✅ Validada (#36 e #48) |
| Painel operacional: agenda do dia, próximas consultas e indicadores por perfil | ✅ #37 (refinamento visual na #47) |
| Aceite ponta a ponta do MVP | ✅ Aceite manual local (#38); evidência final na #45 |

A revalidação de 22/11/2026 sobre a `develop` em `8231632` (PR #132), como no workflow de CI, aprovou 910 testes do backend com MongoDB 7 e o servidor de CPF no ar, 535 testes do frontend em 33 arquivos e 16 testes do módulo `server/`, sem falhas. O lint e o build do frontend passaram. O [relatório de teste OO do servidor de CPF](docs/testes/Relatorio_Teste_OO.pdf) documenta os testes intraclasse e interclasse do PI IV. As validações anteriores e os passos até a versão final estão no [roteiro de entrega](docs/operacao/entrega-mvp.md).

---

## Tecnologias utilizadas

| Camada | Tecnologia | Uso |
|---|---|---|
| Frontend | **React** + **JavaScript** | Interface web responsiva |
| Frontend | Node.js / npm | Gerenciamento de dependências e build |
| Backend | **Java 21** (sem frameworks) | API REST e regras de negócio, sobre o servidor HTTP nativo do JDK (`com.sun.net.httpserver`) |
| Backend | Maven | Build e gerenciamento de dependências |
| Banco de dados | **MongoDB** (via MongoDB Java Driver) | Persistência em coleções de documentos |
| Testes | **JUnit 5** e JUnit 4 | JUnit 5 nos testes de unidade e de integração da API, com MongoDB real; JUnit 4 no módulo `server/` |
| Testes | **Vitest** + Testing Library | Testes das telas e dos serviços do frontend |
| Design | Figma | Protótipo navegável |
| Infraestrutura | Docker Compose | Execução local do MongoDB |

> **Nota:** o servidor Java não utiliza Spring Boot nem outro framework de aplicação. As regras de negócio são implementadas com recursos da própria linguagem.

---

## Arquitetura da aplicação

A aplicação segue o modelo **cliente-servidor**, com o frontend e o backend desacoplados e comunicando-se por uma **API REST** em JSON.

```text
┌──────────────┐   HTTP / JSON   ┌───────────────────────┐   MongoDB Driver   ┌──────────┐
│   Frontend   │ ──────────────► │       Backend         │ ─────────────────► │ MongoDB  │
│  React (SPA) │ ◄────────────── │  Java 21 (API REST)   │ ◄───────────────── │          │
└──────────────┘                 └───────────┬───────────┘                    └──────────┘
                                             │ TCP 3001 (objetos Java serializados)
                                             ▼
                                 ┌───────────────────────┐
                                 │   Servidor de CPF     │
                                 │  Java 21 (server/)    │
                                 └───────────────────────┘
```

O cadastro e a edição de pacientes consultam o **servidor de validação de CPF** (módulo `server/`), um processo Java separado que recebe objetos serializados por socket TCP na porta 3001, atende cada conexão em uma thread e confere o formato e os dígitos verificadores. Sem ele no ar, a API recusa a gravação de pacientes com `503`.

O backend é organizado em três camadas:

1. **Handlers HTTP:** recebem as requisições e convertem os dados de/para JSON.
2. **Serviços:** concentram as regras de agendamento, conflitos, cancelamento, fila de espera e permissões.
3. **Repositórios:** executam as consultas e gravações no banco.

Os dados são organizados nas coleções `usuarios`, `pacientes`, `medicos`, `especialidades`, `agendas`, `bloqueios`, `consultas`, `historico`, `filaEspera` e `notificacoes`, todas com validação (`$jsonSchema`) no próprio banco. Na coleção `consultas`, dois índices únicos parciais, (médico, início) e (paciente, início), restritos às consultas ativas, garantem no banco que apenas uma reserva seja confirmada quando dois usuários disputam a mesma vaga, complementando a validação feita na API. Detalhes em [`docs/`](docs/README.md).

---

## Pré-requisitos e guia inicial de execução

> Para rodar o projeto pela primeira vez, siga o [início rápido](#início-rápido): com um comando só ([opção 1](#opção-1-um-comando)) ou pelo passo a passo ([opção 2](#opção-2-passo-a-passo)). As explicações de cada etapa vêm logo depois.

### Pré-requisitos

| Ferramenta | Versão | Verificação |
|---|---|---|
| Git | 2.x | `git --version` |
| Java JDK | **21** | `java -version` |
| Node.js e npm | **22.22 ou superior** (exigido pelo React Router 8) | `node -v` e `npm -v` |
| Docker Desktop (ou Docker Engine + Compose) | qualquer versão recente, **aberto e em execução** | `docker --version` e `docker ps` |

Não é preciso instalar o Maven nem o MongoDB. O Maven Wrapper (`mvnw`) baixa a versão correta do Maven, e o MongoDB roda no Docker. Se o `node -v` mostrar uma versão anterior à 22.22, atualize o Node (pelo [nvm](https://github.com/nvm-sh/nvm) ou pelo [nvm-windows](https://github.com/coreybutler/nvm-windows), por exemplo), senão o `npm install` falha.

### Início rápido

#### Opção 1: um comando

Com os pré-requisitos instalados e o Docker aberto:

```bash
git clone https://github.com/gabrielsuj/PI_IV-TIME-22.git
cd PI_IV-TIME-22
bash scripts/iniciar.sh            # Linux, macOS ou Git Bash
```

No Windows (PowerShell), troque a última linha por `.\scripts\iniciar.ps1`. Se a política de execução bloquear o script, use `powershell -ExecutionPolicy Bypass -File .\scripts\iniciar.ps1`.

O script faz, em ordem, tudo o que a opção 2 faz à mão:

1. Confere o Java 21+, o Node 22.22+ e se o Docker está respondendo. Também confere se as portas 3000, 3001 e 5173 estão livres.
2. Cria o `.env` a partir do `.env.example`, se ele ainda não existir.
3. Sobe o MongoDB e, se o banco do `.env` estiver vazio, carrega os dados de exemplo.
4. Instala o módulo `server/` no Maven local e roda o `npm install` do frontend quando necessário.
5. Inicia o servidor de CPF e a API, espera a API responder e inicia o frontend.

Quando aparecer **Tudo pronto**, abra http://localhost:5173. Na primeira vez, o processo leva alguns minutos, por causa dos downloads do Maven e do npm. Nas seguintes, ele pula o que já está pronto e mantém os dados do banco.

- **Encerrar:** Ctrl+C no terminal do script encerra o frontend, a API e o servidor de CPF. O MongoDB continua no ar; para pará-lo, use `docker compose stop mongo`.
- **Onde ver as mensagens:**
  - No Bash, as mensagens do servidor de CPF e da API ficam em `logs/cpf.log` e `logs/api.log`.
  - No PowerShell, cada um abre em uma janela própria.
- **Recarregar os dados de exemplo:** `bash scripts/iniciar.sh --recriar` (ou `.\scripts\iniciar.ps1 -Recriar`) apaga e recarrega o banco definido no `.env`.

#### Opção 2: passo a passo

Os mesmos passos do script, feitos à mão.

**1. Baixar o projeto, criar o `.env` e preparar o banco.** Na raiz do repositório:

```bash
git clone https://github.com/gabrielsuj/PI_IV-TIME-22.git
cd PI_IV-TIME-22
cp .env.example .env                            # PowerShell: Copy-Item .env.example .env | cmd: copy .env.example .env
docker compose up -d --wait mongo               # sobe o MongoDB e espera ele ficar pronto
bash scripts/recriar-banco.sh consultafacil     # cria as coleções e carrega os dados de exemplo
```

No Windows sem Git Bash, troque a última linha por `powershell -ExecutionPolicy Bypass -File .\scripts\recriar-banco.ps1 consultafacil`.

> **Não pule o `recriar-banco`.** O Docker cria o banco só com as coleções, sem dados. Sem esse passo, as contas de demonstração não existem e o login falha.

**2. Iniciar os três processos, cada um em um terminal aberto na raiz do projeto e nesta ordem:**

| Terminal | Linux, macOS ou Git Bash | Windows (PowerShell) |
|---|---|---|
| 1. Servidor de CPF (porta 3001) | `(cd server && ../backend/mvnw install -DskipTests)`<br>`java -cp server/target/classes br.edu.puccampinas.consultafacil.Servidor` | `cd server; ..\backend\mvnw.cmd install -DskipTests; cd ..`<br>`java -cp server/target/classes br.edu.puccampinas.consultafacil.Servidor` |
| 2. API (porta 3000) | `cd backend && ./mvnw compile exec:java` | `cd backend; .\mvnw.cmd compile exec:java` |
| 3. Frontend (porta 5173) | `cd frontend && npm install && npm run dev` | `cd frontend; npm install; npm run dev` |

O terminal 1 precisa vir primeiro: a API depende do módulo `server` instalado no Maven local para compilar. Na primeira vez, o Maven e o npm baixam as dependências, e isso pode levar alguns minutos.

#### Conferir se deu certo

Vale para as duas opções:

- O terminal da API (ou o `logs/api.log`, na opção 1 pelo Bash) mostra `Conectado ao Mongo: consultafacil` e `Servidor rodando na porta 3000`.
- Em http://localhost:5173/status, a API aparece como disponível.
- Em http://localhost:5173/login, entre com `secretaria@consultafacil.demo` e a senha `Demo@123`. As outras contas estão em [Demonstração com dados prontos](#demonstração-com-dados-prontos).

#### Problemas comuns na primeira execução

| Sintoma | Causa provável | Solução |
|---|---|---|
| O script `iniciar` para em "Conferindo pré-requisitos" | Falta uma ferramenta ou a versão é antiga | Siga a mensagem de erro e instale ou atualize o que ela indicar |
| `Cannot connect to the Docker daemon` ou `docker compose` falha | O Docker Desktop está fechado | Abra o Docker Desktop, espere ele iniciar e repita o comando |
| Login inválido com as contas de demonstração | O banco está sem dados | Rode `bash scripts/recriar-banco.sh consultafacil` e entre de novo |
| `Could not find artifact ...:server:jar` ao iniciar a API | O módulo `server` não foi instalado | Rode o primeiro comando do terminal 1 antes do terminal 2 |
| Salvar paciente retorna `503` | O servidor de CPF não está rodando | Inicie o terminal 1 e deixe-o aberto |
| `npm install` reclama da versão do Node (`EBADENGINE`) | O Node é anterior à 22.22 | Atualize o Node |
| `Address already in use` | Um processo antigo ainda ocupa a porta 3000 ou 3001 | Encerre o processo antigo (ou feche o terminal antigo) e inicie de novo |
| `./mvnw: Permission denied` | O arquivo perdeu a permissão de execução | `chmod +x backend/mvnw` |
| `UnsupportedClassVersionError ... class file version 71.0` | A IDE recompilou o `server/` com um JDK mais novo | `cd server && ../backend/mvnw clean install -DskipTests` |
| O PowerShell não deixa rodar `recriar-banco.ps1` | Política de execução de scripts | Use `powershell -ExecutionPolicy Bypass -File .\scripts\recriar-banco.ps1 consultafacil` |
| Erro de CORS ou o front aponta para `localhost:8080` | `.env` ausente ou incompleto | Recrie o `.env` a partir do `.env.example` e reinicie a API e o front |

A tabela completa está em [docs/operacao/configuracao.md](docs/operacao/configuracao.md#6-problemas-comuns).

---

Os passos abaixo detalham o início rápido.

### 1. Clonar o repositório

```bash
git clone https://github.com/gabrielsuj/PI_IV-TIME-22.git
cd PI_IV-TIME-22
```

### 2. Configurar variáveis de ambiente

```bash
cp .env.example .env
```

Os valores do `.env.example` já funcionam para rodar localmente, sem nenhuma alteração. Há um único `.env`, na raiz, lido pela API e pelo frontend. Por isso, rode cada módulo a partir da sua pasta (`backend/`, `frontend/`), como nos comandos deste guia. Depois de alterar o `.env`, reinicie a API e o frontend. A descrição de cada variável está em [Variáveis de ambiente](#variáveis-de-ambiente).

### 3. Subir o banco de dados (MongoDB)

```bash
docker compose up -d --wait mongo
bash scripts/recriar-banco.sh consultafacil      # Windows: .\scripts\recriar-banco.ps1 consultafacil
```

O primeiro comando sobe o MongoDB 7 em um container (`consultafacil-mongo`, porta 27017 publicada só em `127.0.0.1`) e espera o banco responder. O segundo apaga **somente o banco informado**, recria as coleções, os validadores e os índices de `database/init/` e carrega os dados de exemplo de `database/seed/`.

Para parar o banco, use `docker compose stop mongo`. Os dados ficam guardados no volume do Docker e voltam no próximo `docker compose up`, então não é preciso rodar o `recriar-banco` de novo.

Atualizar um banco que já existe sem apagar os dados, usar um MongoDB instalado na máquina e conferir a carga são procedimentos descritos em [docs/operacao/banco.md](docs/operacao/banco.md).

#### Bancos por ambiente

| Banco | Uso | Dados de exemplo |
|---|---|---|
| `consultafacil` | Desenvolvimento (padrão do `.env.example`) | sim, depois do `recriar-banco` |
| `consultafacil_demo` | Apresentações, sem interferir no banco de desenvolvimento | sim, depois do `recriar-banco` |
| `consultafacil_test` | Testes JUnit (os testes limpam as coleções que usam) | não |

A API usa o banco definido em `MONGODB_DATABASE` no `.env`. Para apresentar com um banco separado, rode `bash scripts/recriar-banco.sh consultafacil_demo`, troque `MONGODB_DATABASE` para `consultafacil_demo` e reinicie a API.

#### Demonstração com dados prontos

A carga de demonstração simula uma clínica em funcionamento: 9 especialidades (1 desativada), 11 médicos (1 inativo), 10 agendas semanais, bloqueios, 15 usuários, 150 pacientes e cerca de 880 consultas com histórico, distribuídas entre 3 semanas antes e 3 semanas depois do dia da carga. As datas são relativas, então recriar o banco sempre gera consultas passadas (concluídas, faltas e cancelamentos) e futuras (agendadas e confirmadas). Detalhes em [`docs/dicionario/carga-demonstracao.md`](docs/dicionario/carga-demonstracao.md).

| Perfil | E-mail | Senha de demonstração |
|---|---|---|
| Secretaria/administrador | secretaria@consultafacil.demo, lucas.andrade@consultafacil.demo | Demo@123 |
| Médico | ana.martins@consultafacil.demo (e os demais médicos: nome.sobrenome@consultafacil.demo) | Demo@123 |
| Paciente | paciente@consultafacil.demo, carla.souza@consultafacil.demo, diego.santos@consultafacil.demo | Demo@123 |

O menu mostra só as telas de cada perfil, e o servidor recusa (403) o acesso a dados de outro perfil.

| Perfil | O que mostrar |
|---|---|
| Secretaria | Cadastros de pacientes, médicos e especialidades; **Agenda** (jornada semanal e bloqueios, com aviso quando a mudança atinge consultas marcadas); **Calendário** por médico; **Nova consulta** para qualquer paciente; **Consultas** (todas, com detalhe e cancelamento) |
| Médico (Ana) | **Agenda** e **Calendário** abrem diretamente o cadastro vinculado ao login; **Consultas** que atende; leitura de pacientes |
| Paciente (Paulo) | **Consultas** próprias, com cancelamento e histórico; avisos de vaga e notificações. Agendamento somente pela secretaria |

A tela **Nova consulta** (`/nova-consulta`) é exclusiva da secretaria. Os horários livres consideram a jornada, a duração das consultas, os bloqueios e as consultas já marcadas. Ao cancelar uma consulta, a vaga volta para a busca e os pacientes compatíveis da fila de espera são avisados. Os contratos da API estão em [docs/api/](docs/README.md#api-rest).

### 4. Executar o backend

O backend tem dois processos Java: o servidor de validação de CPF (`server/`, porta 3001) e a API (`backend/`, porta 3000). Sem o servidor de CPF, a API não compila e recusa gravar pacientes com `503`.

**Terminal 1: servidor de CPF.** Na raiz do repositório:

```bash
(cd server && ../backend/mvnw install -DskipTests)   # instala o módulo no Maven local (só na primeira vez ou quando server/ mudar)
java -cp server/target/classes br.edu.puccampinas.consultafacil.Servidor
```

No PowerShell: `cd server; ..\backend\mvnw.cmd install -DskipTests; cd ..` e depois o mesmo comando `java`.

**Terminal 2: API.**

```bash
cd backend
./mvnw compile exec:java          # Windows: .\mvnw.cmd compile exec:java
```

A API mostra `Conectado ao Mongo: <banco>` e `Servidor rodando na porta 3000`. As sessões ficam em memória, então, ao reiniciar a API, é preciso entrar de novo.

### 5. Executar o frontend

**Terminal 3:**

```bash
cd frontend
npm install                  # instala as dependências (só na primeira vez)
npm run dev                  # inicia em http://localhost:5173
```

A tela **Status do sistema** (`/status`) indica se a API responde. Outros scripts: `npm run build` (build de produção em `dist/`), `npm run preview` (serve o build em `http://localhost:4173`) e `npm run lint` (ESLint). Observações sobre build, preview e implantação estão em [docs/operacao/configuracao.md](docs/operacao/configuracao.md#7-observações-sobre-execução-e-implantação).

### 6. Executar os testes

Com o MongoDB no ar e o servidor de CPF rodando na porta 3001 (terminal 1):

```bash
bash scripts/recriar-banco.sh consultafacil_test   # validadores e índices, sem dados
(cd backend && ./mvnw test)                       # JUnit 5: unidade e integração
(cd frontend && npm ci && npm test && npm run lint && npm run build)
```

A estratégia de testes, a cobertura dos cenários de aceite e os testes opcionais estão em [`docs/testes/estrategia-de-testes.md`](docs/testes/estrategia-de-testes.md), incluindo o [comando completo equivalente à CI](docs/testes/estrategia-de-testes.md#validação-completa-como-na-ci). A execução básica acima não ativa a persistência opcional de histórico, os cadastros com schema real nem a conferência dos indicadores com a carga.

Em cada pull request, o [workflow de CI](.github/workflows/ci.yml) roda o JUnit com MongoDB 7 e os checks do frontend com `npm test`, `npm run lint` e `npm run build`.

### 7. Backup e recuperação

```bash
bash scripts/backup-banco.sh consultafacil_demo        # gera backups/<banco>-<data>.archive.gz
bash scripts/testar-recuperacao.sh consultafacil_demo  # confere que o backup restaura tudo, sem alterar o banco
```

Para restaurar, passe o arquivo gerado a `bash scripts/restaurar-banco.sh`. Consulte o [procedimento completo](docs/operacao/backup-recuperacao.md).

### Variáveis de ambiente

| Variável | Descrição | Exemplo |
|---|---|---|
| `MONGODB_URI` | String de conexão do MongoDB | `mongodb://localhost:27017/?serverSelectionTimeoutMS=5000` |
| `MONGODB_DATABASE` | Banco usado pela API (ver "Bancos por ambiente") | `consultafacil` |
| `SERVER_PORT` | Porta da API | `3000` |
| `CPF_SERVER_HOST` | Endereço do servidor TCP de validação de CPF | `localhost` |
| `CPF_SERVER_PORT` | Porta do servidor TCP de CPF (padrão 3001, diferente da API) | `3001` |
| `CORS_ALLOWED_ORIGINS` | Origens permitidas do frontend, separadas por vírgula | `http://localhost:5173` |
| `SESSION_TTL_MINUTES` | Duração da sessão, em minutos | `60` |
| `NOTIFICACOES_ANTECEDENCIA_HORAS` | Quantas horas antes da consulta o lembrete é enviado (#34) | `24` |
| `NOTIFICACOES_INTERVALO_SEGUNDOS` | Intervalo do agendador que gera e envia os lembretes | `60` |
| `NOTIFICACOES_EMAIL_TESTE` | `true` grava uma cópia de cada notificação do paciente no canal `EMAIL_TESTE`, "enviada" para o log do servidor com o e-mail mascarado | `false` |
| `VITE_API_BASE_URL` | URL da API usada pelo frontend (mesma porta de `SERVER_PORT`) | `http://localhost:3000` |

> Nunca versione o arquivo `.env`. Utilize apenas o `.env.example`, sem segredos reais.

O valor de cada variável quando ela não é definida, as variáveis usadas só pelos testes e uma tabela de problemas comuns (porta ocupada, CPF indisponível, CORS) estão em [docs/operacao/configuracao.md](docs/operacao/configuracao.md).

---

## Documentação

O índice completo está em [`docs/README.md`](docs/README.md):

- **Projeto:** relatório consolidado (ideação, requisitos, cronograma e cenários de aceite) e apresentação.
- **Modelagem do banco:** um documento por coleção, além da carga de demonstração.
- **API:** autenticação e health, pacientes, médicos e especialidades, agendamento, consultas, cancelamento, fila de espera, notificações, indicadores e alterações de agenda.
- **Testes:** estratégia, cobertura do Quadro 4, garantias de reserva, comandos e relatório de teste OO do servidor de CPF.
- **Operação:** configuração, manutenção do banco, backup e recuperação, roteiros por perfil, evidências de validação e preparação da tag/release.

---

## Estrutura de pastas

Estrutura resumida:

```text
consultafacil/
├── backend/                 # API REST em Java
│   ├── pom.xml
│   └── src/
│       ├── main/java/       # handlers, serviços, repositórios e domínio
│       └── test/java/       # testes JUnit (unidade e integração)
├── frontend/                # Aplicação React (JavaScript) com Vite
│   ├── package.json
│   ├── vite.config.js       # build, servidor de desenvolvimento e testes
│   └── src/
│       ├── main.jsx         # ponto de entrada
│       ├── routes/          # tabela de rotas (React Router)
│       ├── pages/           # telas
│       ├── components/      # componentes reutilizáveis
│       ├── hooks/           # hooks de estado e efeitos
│       ├── services/        # cliente HTTP centralizado (api.js) e serviços por recurso
│       ├── styles/          # estilos globais
│       └── __tests__/       # testes (Vitest + Testing Library)
├── server/                  # Módulo Java separado de comunicação/validação de CPF
├── database/
│   ├── init/                # Coleções, validadores ($jsonSchema) e índices
│   └── seed/                # Dados fictícios de demonstração
├── scripts/                 # iniciar (sobe tudo), recriar-banco, backup/restauração/teste de recuperação e verificadores
├── docs/                    # Documentação (índice em docs/README.md)
│   ├── dicionario/          # Uma coleção por documento
│   ├── api/                 # Contratos das rotas
│   ├── operacao/            # Configuração, backup e roteiro de entrega
│   └── testes/              # Estratégia e relatórios de testes
├── docker-compose.yml       # MongoDB para desenvolvimento local
├── .env.example             # Modelo das variáveis de ambiente
└── README.md
```

---

## Informações acadêmicas e autoria

| | |
|---|---|
| **Instituição** | Pontifícia Universidade Católica de Campinas (PUC-Campinas) |
| **Escola** | Escola Politécnica |
| **Curso** | Engenharia de Software |
| **Disciplina** | Ideação e Validação em Engenharia de Software |
| **Professora** | Profa. Dra. Renata Arantes |
| **Ano** | 2026 |

### Integrantes do grupo

| Nome | RA |
|---|---|
| Davi José Bertuolo Vitoreti | 25004168 |
| Gabriel Martins de Almeida | 25006162 |
| Murilo Moraes | 25000073 |
| Murilo Rigoni | 25006049 |
| Vinicius Valim de Vechi Cardoso | 25000387 |

### Referências

- BELTRAME, S. M. et al. Absenteísmo de usuários como fator de desperdício: desafio para sustentabilidade em sistema universal de saúde. *Saúde em Debate*, v. 43, n. 123, p. 1015-1030, 2019. DOI: [10.1590/0103-1104201912303](https://doi.org/10.1590/0103-1104201912303).
- DANTAS, L. F. et al. No-shows in appointment scheduling: a systematic literature review. *Health Policy*, v. 122, n. 4, p. 412-421, 2018. DOI: [10.1016/j.healthpol.2018.02.002](https://doi.org/10.1016/j.healthpol.2018.02.002).
- ROBOTHAM, D. et al. Using digital notifications to improve attendance in clinic: systematic review and meta-analysis. *BMJ Open*, v. 6, n. 10, e012116, 2016. DOI: [10.1136/bmjopen-2016-012116](https://doi.org/10.1136/bmjopen-2016-012116).
- SILVEIRA, G. S. et al. Prevalência de absenteísmo em consultas médicas em unidade básica de saúde do sul do Brasil. *Revista Brasileira de Medicina de Família e Comunidade*, v. 13, n. 40, p. 1-7, 2018. DOI: [10.5712/rbmfc13(40)1836](https://doi.org/10.5712/rbmfc13(40)1836).

### Licença

A definir. Este repositório foi desenvolvido para fins acadêmicos, e todos os dados de demonstração são fictícios.
