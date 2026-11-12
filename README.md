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

---

## Tecnologias utilizadas

| Camada | Tecnologia | Uso |
|---|---|---|
| Frontend | **React** + **JavaScript** | Interface web responsiva |
| Frontend | Node.js / npm | Gerenciamento de dependências e build |
| Backend | **Java 21** (sem frameworks) | API REST e regras de negócio, sobre o servidor HTTP nativo do JDK (`com.sun.net.httpserver`) |
| Backend | Maven | Build e gerenciamento de dependências |
| Banco de dados | **MongoDB** (via MongoDB Java Driver) | Persistência em coleções de documentos |
| Testes | **JUnit 5** | Testes de unidade e de integração da API |
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
└──────────────┘                 └───────────────────────┘                    └──────────┘
```

O backend é organizado em três camadas:

1. **Handlers HTTP:** recebem as requisições e convertem os dados de/para JSON.
2. **Serviços:** concentram as regras de agendamento, conflitos, cancelamento, fila de espera e permissões.
3. **Repositórios:** executam as consultas e gravações no banco.

Os dados são organizados nas coleções `pacientes`, `medicos`, `especialidades`, `agendas`, `consultas`, `filaEspera` e `historico`. Na coleção `consultas`, um índice único composto (médico, data e horário), restrito às consultas ativas, garante no próprio banco que apenas uma reserva seja confirmada quando dois usuários disputam a mesma vaga, complementando a validação feita na API.

---

## Pré-requisitos e guia inicial de execução

> 🚧 **Projeto em fase inicial.** Os comandos abaixo serão atualizados conforme o código evoluir. Trechos marcados com `TODO` ainda dependem de implementação.

### Pré-requisitos

| Ferramenta | Versão sugerida | Verificação |
|---|---|---|
| Git | 2.x | `git --version` |
| Java JDK | 21 | `java -version` |
| Maven | 3.9+ (ou Maven Wrapper do projeto) | `mvn -version` |
| Node.js e npm | 22.22+ (exigido pelo React Router 8) | `node -v` e `npm -v` |
| MongoDB | 7.x (local) **ou** Docker + Docker Compose | `mongod --version` / `docker --version` |

### 1. Clonar o repositório

```bash
git clone https://github.com/<organizacao-ou-usuario>/consultafacil.git
cd consultafacil
```

### 2. Configurar variáveis de ambiente

```bash
cp .env.example .env
# TODO: ajustar os valores em .env (string de conexão do MongoDB, porta da API, segredo do token)
```

O mesmo `.env` da raiz é lido pelo backend e pelo frontend (o Vite está configurado com `envDir: '..'`). Mantenha `VITE_API_BASE_URL` apontando para a porta definida em `SERVER_PORT`.

### 3. Subir o banco de dados (MongoDB)

Com Docker Compose:

```bash
docker compose up -d mongo
```

Na primeira execução, os scripts de `database/init/` criam as coleções, os validadores e os índices. Eles só rodam com o volume vazio; para recriar o banco local, use `docker compose down -v` antes do `up`.

#### Bancos por ambiente

| Banco | Uso | Dados de exemplo |
|---|---|---|
| `consultafacil` | Desenvolvimento (padrão do `.env.example`) | sim |
| `consultafacil_test` | Testes JUnit (os testes limpam as coleções que usam) | não |
| `consultafacil_demo` | Apresentações, sem interferir no banco de desenvolvimento | sim |

A API usa o banco definido em `MONGODB_DATABASE` no `.env`.

Para recriar qualquer um deles do zero (apaga o banco, executa `database/init/` e, exceto no de testes, `database/seed/`):

```bash
scripts/recriar-banco.sh                      # consultafacil
scripts/recriar-banco.sh consultafacil_test
scripts/recriar-banco.sh consultafacil_demo
```

#### Demonstração com dados prontos

Com o container Mongo em funcionamento, execute na raiz do projeto:

PowerShell (Windows):

```powershell
.\scripts\recriar-banco.ps1 consultafacil_demo
```

Bash (Linux, macOS ou Git Bash):

```bash
bash scripts/recriar-banco.sh consultafacil_demo
```

O comando apaga **somente o banco selecionado**, reaplica os schemas e índices e carrega, em ordem, os scripts de `database/seed/`. A recriação de `consultafacil_demo` não apaga os bancos de desenvolvimento ou testes. O script PowerShell usa demonstração como padrão; o Bash mantém o padrão existente de desenvolvimento. Ambos deixam `consultafacil_test` sem seeds.

A carga inclui 2 especialidades, 2 médicos, 2 agendas semanais, 1 bloqueio de exemplo, 3 usuários e 3 pacientes fictícios. Os IDs fixos permitem repetir os seeds sem duplicar registros. O seed de usuários preserva senhas existentes; a recriação completa restaura a senha de demonstração.

| Perfil | E-mail | Senha de demonstração |
|---|---|---|
| Secretaria/administrador | secretaria@consultafacil.demo | Demo@123 |
| Médico | ana.martins@consultafacil.demo | Demo@123 |
| Paciente | paciente@consultafacil.demo | Demo@123 |

As senhas são armazenadas pelo seed existente com PBKDF2 compatível com `SenhaHasher`. O login da médica está vinculado ao cadastro de Ana; Paulo aparece tanto como usuário quanto como paciente, com o mesmo CPF e e-mail.

Configure `MONGODB_DATABASE=consultafacil_demo` no `.env` e reinicie a API antes de entrar em `/login`. Use a conta da secretaria para cadastrar e editar pacientes; médicos têm acesso de leitura aos pacientes e acesso às agendas e bloqueios. O perfil paciente pode autenticar, mas não tem acesso a essas rotas administrativas.

A agenda carrega os médicos de `GET /medicos`, disponível para médico e secretaria autenticados. Selecione Ana em `/agenda` ou abra diretamente `/agenda?medicoId=100000000000000000000001`. O bloqueio existente é de **26/10/2026, das 09h às 10h**, no fuso de São Paulo; sua data foi preservada. O contrato do cadastro está em [docs/api/medicos-especialidades.md](docs/api/medicos-especialidades.md).

Em **Nova consulta** (`/nova-consulta`), a secretaria escolhe o paciente, a especialidade, o médico e a data, e confirma um dos horários livres. O paciente Paulo também pode agendar para si mesmo. Os horários livres consideram a jornada, a duração das consultas, os bloqueios e as consultas já marcadas. O contrato está em [docs/api/agendamento.md](docs/api/agendamento.md).

Para verificar a modelagem:

```bash
docker exec -i consultafacil-mongo mongosh consultafacil --quiet < scripts/verificar-modelagem.js
```

Detalhes da modelagem em [`docs/modelagem/medicos-agenda.md`](docs/modelagem/medicos-agenda.md) e [`docs/modelagem/consultas.md`](docs/modelagem/consultas.md).

Ou use uma instância local do MongoDB, apontando `MONGODB_URI` para ela.

### 4. Executar o backend

```bash
cd backend
mvn clean install            # TODO: compilar e executar os testes JUnit
mvn exec:java                # TODO: iniciar a API (porta padrão prevista: 8080)
```

### 5. Executar o frontend

Em outro terminal:

```bash
cd frontend
npm install                  # instala as dependências
npm run dev                  # inicia em http://localhost:5173
```

A página inicial e a tela **Status do sistema** (`/status`) consultam o `GET /health` da API e indicam se ela está disponível. Outros scripts: `npm run build` (build de produção em `dist/`), `npm run preview` (serve o build) e `npm run lint` (ESLint).

### 6. Executar os testes

```bash
cd backend && mvn test       # TODO: testes de unidade e de integração (JUnit 5)
cd frontend && npm test      # testes do frontend (Vitest + Testing Library)
```

### Variáveis de ambiente

| Variável | Descrição | Exemplo |
|---|---|---|
| `MONGODB_URI` | String de conexão do MongoDB | `mongodb://localhost:27017/?serverSelectionTimeoutMS=5000` |
| `MONGODB_DATABASE` | Banco usado pela API (ver "Bancos por ambiente") | `consultafacil` |
| `SERVER_PORT` | Porta da API | `3000` |
| `CORS_ALLOWED_ORIGINS` | Origens permitidas do frontend, separadas por vírgula | `http://localhost:5173` |
| `SESSION_TTL_MINUTES` | Duração da sessão, em minutos | `60` |
| `VITE_API_BASE_URL` | URL da API usada pelo frontend (mesma porta de `SERVER_PORT`) | `http://localhost:3000` |

> Nunca versione o arquivo `.env`. Utilize apenas o `.env.example`, sem segredos reais.

---

## Estrutura de pastas

Estrutura resumida (sujeita a ajustes durante o desenvolvimento):

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
├── database/                # Scripts de inicialização do MongoDB (coleções, índices, dados de demonstração)
├── docs/                    # Relatórios, modelagem, protótipo e demais artefatos acadêmicos
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
