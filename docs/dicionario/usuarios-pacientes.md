# Dicionário de Dados do Banco de Dados MongoDB do Sistema de Agendamento de Consultas com Fila de Espera ConsultaFácil

## Histórico de Versões/Revisões

| Data | Autor | Versão | Comentários |
|------|-------|--------|-------------|
| 2026-11-03 | Davi Vitoreti | v1.0.0 | Versão inicial com a especificação das coleções: `usuarios` e `pacientes`. |

## Visão Geral

O ConsultaFácil utiliza MongoDB via MongoDB Java Driver. Este documento descreve os campos instalados por [`05-esquema-usuarios.js`](../../database/init/05-esquema-usuarios.js) e [`04-esquema-pacientes.js`](../../database/init/04-esquema-pacientes.js). Esses arquivos são a fonte executável dos validadores e índices.

Os identificadores persistidos são `ObjectId`; a API apresenta IDs como strings hexadecimais de 24 caracteres. Datas BSON usam UTC. O cadastro de paciente e a conta de login são entidades distintas: cadastrar um paciente pela secretaria não cria uma conta em `usuarios`.

## Relacionamento entre as coleções

```text
usuarios._id ───── pacientes.usuarioId (opcional)
       └───────── medicos.usuarioId   (opcional)

pacientes._id ──── consultas.pacienteId
       └───────── filaEspera.pacienteId
```

Todo login é um registro de `usuarios`, com perfil `PACIENTE`, `MEDICO` ou `SECRETARIA_ADMIN`. Um paciente cadastrado pela secretaria pode não ter login e continua podendo receber agendamentos e inscrições na fila pela secretaria.

O autoatendimento do perfil `PACIENTE` exige um documento em `pacientes` cujo `usuarioId` corresponda ao usuário da sessão. `PacienteDoUsuarioRepository` consulta esse vínculo; não usa coincidência de CPF ou e-mail como substituto. A carga de demonstração cria os vínculos necessários. Os endpoints atuais de pacientes não expõem nem editam `usuarioId`.

## Coleções

### 1. usuarios

**Descrição:** Credenciais, identidade de exibição e perfil de acesso. O MVP não oferece endpoints de criação, edição, autocadastro ou desativação de usuários; as contas demonstrativas vêm dos seeds. O login lê essa coleção e atualiza `ultimoAcessoEm`.

| Campo | Tipo BSON | Obrigatório | Regra instalada |
|---|---|:-:|---|
| `_id` | `objectId` | Identificador MongoDB | Referenciado pelos vínculos e atores do histórico |
| `nome` | `string` | Sim | De 3 a 120 caracteres, sem espaços nas extremidades |
| `cpf` | `string` | Sim | 11 dígitos, sem máscara; único na coleção |
| `email` | `string` | Sim | Formato de e-mail em minúsculas; único na coleção |
| `senha` | `object` | Sim | Objeto fechado com `hash`, `salt`, `algoritmo`, `iteracoes` |
| `perfil` | literal | Sim | `PACIENTE`, `MEDICO` ou `SECRETARIA_ADMIN` |
| `ativo` | `bool` | Sim | O login recusa o usuário desativado |
| `ultimoAcessoEm` | `date` | Não | Atualizado após login bem-sucedido |
| `criadoEm` | `date` | Sim | Data de criação |
| `atualizadoEm` | `date` | Sim | Data de atualização |

O validador de `usuarios` recusa propriedades não previstas. A obrigatoriedade de `cpf` e o índice único de CPF fazem parte do schema atual.

**Estrutura de `senha`:**

| Campo | Tipo BSON | Regra |
|---|---|---|
| `hash` | `string` | Hash em Base64 |
| `salt` | `string` | Salt em Base64 |
| `algoritmo` | `string` | `SenhaHasher` usa `PBKDF2WithHmacSHA256` |
| `iteracoes` | `int` | Maior ou igual a 1 no schema; padrão do hasher: 210.000 |

`SenhaHasher` gera salt aleatório de 16 bytes e hash de 256 bits. A senha em texto puro não é persistida. O objeto de credenciais não é retornado pelo login nem por `/auth/me`; essas respostas apresentam apenas a identidade da sessão.

**Índices instalados:**

| Chave | Tipo | Finalidade |
|---|---|---|
| `{ email: 1 }` | Único | Identificador de login sem duplicidade |
| `{ cpf: 1 }` | Único | CPF sem duplicidade entre contas |
| `{ perfil: 1, ativo: 1 }` | Comum | Consulta por perfil e estado |

As sessões ficam em memória, fora de `usuarios`, com expiração controlada por `SESSION_TTL_MINUTES`. Reiniciar a API exige novo login. Contrato e erros em [autenticacao.md](../api/autenticacao.md).

### 2. pacientes

**Descrição:** Cadastro mínimo da pessoa atendida, referenciada por consultas e fila de espera. O schema efetivamente instalado exige apenas `nome`, `cpf` e `email`. Os campos de nascimento, telefone, preferências de notificação e desativação da especificação inicial não são obrigatórios nem editáveis na API atual.

| Campo | Tipo BSON | Obrigatório | Regra instalada |
|---|---|:-:|---|
| `_id` | `objectId` | Identificador MongoDB | ID apresentado na API como `id` |
| `nome` | `string` | Sim | Deve conter ao menos um caractere não branco |
| `cpf` | `string` | Sim | Exatamente 11 dígitos; único |
| `email` | `string` | Sim | Formato de e-mail, até 254 caracteres; único |
| `usuarioId` | `objectId` nas cargas | Não | Vínculo opcional usado no autoatendimento; preservado nas edições da API |

O validador atual de pacientes permite propriedades adicionais e não valida o tipo nem a unicidade de `usuarioId`. Os seeds criam esse campo como `ObjectId`, e os serviços de autoatendimento dependem desse formato. A API de cadastro grava explicitamente apenas `_id`, `nome`, `cpf` e `email`; na edição, atualiza os três campos com `$set`, preservando os demais.

**Exemplo mínimo criado pela API:**

```json
{
  "_id": { "$oid": "100000000000000000000001" },
  "nome": "Paulo Paciente",
  "cpf": "52998224725",
  "email": "paulo.paciente@example.com"
}
```

**Índices instalados:**

| Nome | Chave | Tipo |
|---|---|---|
| `uk_paciente_cpf` | `{ cpf: 1 }` | Único |
| `uk_paciente_email` | `{ email: 1 }` | Único |

Não há índice de `nome` nem índice único/parcial de `usuarioId` no script atual. A busca da API faz correspondência parcial por nome, e-mail ou CPF e ordena por nome e ID.

**Validações e permissões da API:**

- `nome`, `cpf` e `email` são textos obrigatórios. A API remove espaços nas extremidades, normaliza o CPF sem máscara e o e-mail para minúsculas.
- O CPF também passa pela verificação dos dígitos verificadores. A API valida o formato do e-mail e a unicidade de CPF/e-mail, complementada pelos índices do banco.
- `SECRETARIA_ADMIN` pode cadastrar, listar, consultar e editar pacientes. O PUT exige novamente os três campos editáveis.
- `MEDICO` pode listar e consultar o cadastro, em modo de leitura, sem filtro por atendimento no serviço atual.
- `PACIENTE` não acessa `/pacientes`. O vínculo da sessão é usado nas rotas próprias de consultas, fila e notificações; o agendamento é realizado pela secretaria, inclusive para pacientes com conta.
- Não há exclusão, desativação ou registro de alterações cadastrais em `historico` por esses endpoints. O histórico implementado acompanha os eventos das consultas.

O contrato HTTP, a busca, os códigos de erro e os testes estão em [pacientes.md](../api/pacientes.md). As preferências de comunicação da especificação inicial não controlam o envio atual; consulte o [modelo de notificações](notificacoes.md) e a [API da central](../api/notificacoes.md).

## Inicialização e conferência

Execute os scripts versionados em `database/init/`, em vez de copiar um schema deste documento. Eles criam ou atualizam os validadores e índices preservando os documentos existentes. Os comandos de [atualização de banco existente](../../README.md#3-subir-o-banco-de-dados-mongodb) aplicam todos os scripts, incluindo dependências entre coleções.

Para um banco novo de demonstração, `bash scripts/recriar-banco.sh consultafacil_demo` instala os schemas e os seeds; esse comando apaga o banco selecionado. A carga e os vínculos de login estão em [carga-demonstracao.md](carga-demonstracao.md).

Os testes `PacienteValidatorTest`, `PacienteServiceTest`, `PacienteHandlerTest`, `AuthHandlerTest` e `RotasAutorizacaoTest` cobrem as regras e permissões. Consulte a [estratégia de testes](../testes/estrategia-de-testes.md) para preparar os bancos e executar as suítes completas.
