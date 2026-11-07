# Dicionário de Dados do Banco de Dados MongoDB do Sistema de Agendamento de Consultas com Fila de Espera ConsultaFácil

## Histórico de Versões/Revisões

| Data | Autor | Versão | Comentários |
|------|-------|--------|-------------|
| 2026-11-03 | Davi Vitoreti | v1.0.0 | Versão inicial com a especificação das coleções: `usuarios` e `pacientes`. |

## Visão Geral

O ConsultaFácil utiliza o MongoDB (acesso via MongoDB Java Driver) para persistir cadastros, agendas, consultas, fila de espera e histórico. Este documento especifica as coleções `usuarios` e `pacientes`, que sustentam as funcionalidades **Acesso** (login e autorização por perfil) e **Pacientes** (cadastro, edição, listagem e consulta) do MVP.

Convenções adotadas em todas as coleções:

- Nomes de coleções e de campos em `camelCase`, sem acentos.
- Identificador `_id` do tipo `ObjectId`, gerado pelo MongoDB.
- Datas e horários do tipo `Date` (ISODate), armazenados em UTC. A conversão para o fuso de Brasília ocorre na apresentação.
- Campos opcionais ausentes não são gravados (não se usa `null`).
- Registros não são excluídos fisicamente. A desativação é lógica (campo `ativo`), para preservar a integridade do histórico de alterações e das consultas já realizadas.

## Relacionamento entre as coleções

```
usuarios (1) ────── (0..1) pacientes
   │                          │
   │ perfil = MEDICO          │ _id referenciado por consultas
   └── medicos.usuarioId      └── e filaEspera (pacienteId)
```

- Todo **login** é um registro em `usuarios`, qualquer que seja o perfil (Paciente, Médico ou Secretaria/Administrador).
- Todo **paciente** é um registro em `pacientes`. O vínculo com `usuarios` é **opcional**: um paciente cadastrado pela secretaria pode não ter acesso próprio ao sistema (sem autoatendimento) e continua podendo ser agendado, cancelado e inserido na fila.
- O vínculo é feito por referência (`pacientes.usuarioId` → `usuarios._id`), pois as duas coleções têm ciclos de vida e permissões de acesso distintos.

## Coleções

A seguir são documentadas as coleções e seus atributos.

### 1. usuarios

**Descrição:** Representa uma pessoa autorizada a acessar o sistema, com suas credenciais e seu perfil de acesso. Define o que cada pessoa pode fazer (regra do relatório: "cada perfil acessa apenas as informações permitidas"; o servidor bloqueia o acesso a funções de outro perfil). Usuários com perfil `MEDICO` são complementados pela coleção `medicos`, e usuários com perfil `PACIENTE` pela coleção `pacientes`.

**Atributos:**

| Nome | Tipo | Obrigatoriedade | Exemplos/Comentários |
|------|------|-----------------|----------------------|
| `_id` | ObjectId | Sim | Gerado automaticamente. Ex.: `ObjectId("6720a1f3c2b4d5e6f7a8b9c0")`. Identifica o usuário nas demais coleções (`historico.usuarioId`, `pacientes.usuarioId`, `medicos.usuarioId`). |
| `nome` | String | Sim | Nome de exibição do usuário. De 3 a 120 caracteres, sem espaços nas extremidades. Ex.: `"Maria Aparecida Souza"`. |
| `cpf` | String | Sim | CPF do paciente, único documento de identificação aceito. Somente os 11 dígitos, **sem máscara** (a formatação é feita na apresentação). Os dígitos verificadores são validados na API. **Único** na coleção. Ex.: `"12345678909"`. |
| `email` | String | Sim | Identificador de login. Armazenado sempre em **minúsculas** e validado quanto ao formato. **Único** na coleção. Também é o destino das notificações por e-mail de teste do MVP. Ex.: `"maria.souza@clinicaexemplo.com.br"`. |
| `senha` | Objeto embutido | Sim | Credencial protegida. A senha em texto puro **nunca** é armazenada nem registrada em logs. Estrutura descrita na tabela *senha* abaixo. |
| `perfil` | Lista literal e estática de valores | Sim | Valores aceitos: `"PACIENTE"`, `"MEDICO"`, `"SECRETARIA_ADMIN"`. Corresponde aos três perfis do MVP. Não devem ser acrescentados valores sem revisão deste documento e das regras de permissão da API. |
| `ativo` | Booleano | Sim | `true` por padrão. Usuários com `false` não conseguem autenticar, mas permanecem referenciados no histórico. |
| `ultimoAcessoEm` | Data e hora | Não | Atualizado a cada login bem-sucedido. Ausente enquanto o usuário nunca acessou o sistema. |
| `criadoEm` | Data e hora | Sim | Preenchido pelo servidor na criação. Não pode ser alterado. |
| `atualizadoEm` | Data e hora | Sim | Preenchido pelo servidor a cada alteração. Na criação, é igual a `criadoEm`. |

**Estrutura do objeto `senha`:**

| Nome | Tipo | Obrigatoriedade | Exemplos/Comentários |
|------|------|-----------------|----------------------|
| `senha.hash` | String (Base64) | Sim | Resultado da derivação da senha. Nunca é retornado em respostas da API. |
| `senha.salt` | String (Base64) | Sim | Valor aleatório de, no mínimo, 16 bytes, único por usuário. |
| `senha.algoritmo` | String | Sim | Sugestão para o MVP: `"PBKDF2WithHmacSHA256"`, disponível no próprio JDK 21 (compatível com a decisão de não usar frameworks no servidor). Permite trocar o algoritmo no futuro sem invalidar contas antigas. |
| `senha.iteracoes` | Inteiro | Sim | Número de iterações do algoritmo. Ex.: `210000`. Armazenado para permitir aumento gradual do custo. |

**Índices:**

| Índice | Tipo | Finalidade |
|--------|------|------------|
| `{ email: 1 }` | Único | Impede dois logins com o mesmo e-mail e acelera a autenticação. |
| `{ perfil: 1, ativo: 1 }` | Comum | Listagens por perfil (ex.: usuários ativos da secretaria). |

**Exemplo de documento:**

```json
{
  "_id": { "$oid": "6720a1f3c2b4d5e6f7a8b9c0" },
  "nome": "Maria Aparecida Souza",
  "email": "maria.souza@clinicaexemplo.com.br",
  "senha": {
    "hash": "k3Jx...(Base64)...",
    "salt": "9fQ2...(Base64)...",
    "algoritmo": "PBKDF2WithHmacSHA256",
    "iteracoes": 210000
  },
  "perfil": "SECRETARIA_ADMIN",
  "ativo": true,
  "ultimoAcessoEm": { "$date": "2026-11-02T13:45:10Z" },
  "criadoEm": { "$date": "2026-10-09T12:00:00Z" },
  "atualizadoEm": { "$date": "2026-10-09T12:00:00Z" }
}
```

**Regras de negócio e integridade:**

- O `email` é normalizado (minúsculas, sem espaços) antes de qualquer consulta ou gravação.
- A autorização é verificada no **servidor** a cada requisição, com base em `perfil`. A interface apenas oculta funções, não as protege.
- O cadastro de usuários com perfil `MEDICO` ou `SECRETARIA_ADMIN` é restrito ao perfil `SECRETARIA_ADMIN`. O perfil `PACIENTE` pode ser criado por autocadastro ou pela secretaria.
- Toda criação, desativação e troca de perfil é registrada na coleção `historico`.

---

### 2. pacientes

**Descrição:** Representa a pessoa física que recebe atendimento na clínica. É a entidade referenciada por consultas e pela fila de espera. É importante destacar que **pacientes que nunca agendaram uma consulta devem ser representados** (o cadastro pode ser feito previamente pela secretaria), e que pacientes **sem login** também são válidos (ver relacionamento com `usuarios`).

Por ser dado pessoal (e potencialmente sensível, por estar ligado a um atendimento de saúde), a coleção segue o princípio de **minimização**: armazena apenas o necessário para a agenda, a identificação e a comunicação. Dados clínicos (prontuário), convênios e pagamentos estão fora do MVP.

**Atributos:**

| Nome | Tipo | Obrigatoriedade | Exemplos/Comentários |
|------|------|-----------------|----------------------|
| `_id` | ObjectId | Sim | Gerado automaticamente. É o valor gravado em `consultas.pacienteId` e `filaEspera.pacienteId`. |
| `usuarioId` | ObjectId (referência a `usuarios._id`) | Não | Presente somente quando o paciente possui login para autoatendimento. O usuário referenciado deve ter `perfil = "PACIENTE"`. **Único** quando presente. |
| `nome` | String | Sim | Nome completo do paciente, de 3 a 120 caracteres, sem espaços nas extremidades. Ex.: `"João Pedro da Silva"`. |
| `cpf` | String | Sim | CPF do paciente, único documento de identificação aceito. Somente os 11 dígitos, **sem máscara** (a formatação é feita na apresentação). Os dígitos verificadores são validados na API. **Único** na coleção. Ex.: `"12345678909"`. |
| `dataNascimento` | Data | Sim | Somente data (hora `00:00:00` UTC). Não pode ser futura. |
| `telefone` | String | Sim | Somente dígitos com DDI e DDD, sem formatação (padrão E.164). Ex.: `"+5519999998888"`. Reservado para os envios reais por SMS/WhatsApp, previstos como evolução futura. |
| `email` | String | Sim | Em minúsculas e validado quanto ao formato. Destino das notificações por e-mail de teste do MVP. Pode diferir do e-mail de login. Ex.: `"joao.silva@email.com"`. |
| `preferenciasNotificacao` | Objeto embutido | Sim | Preferências de comunicação do paciente. Estrutura descrita na tabela abaixo. |
| `ativo` | Booleano | Sim | `true` por padrão. Pacientes inativos não aparecem na busca de agendamento, mas permanecem nas consultas e no histórico existentes. |
| `criadoEm` | Data e hora | Sim | Preenchido pelo servidor na criação. Não pode ser alterado. |
| `atualizadoEm` | Data e hora | Sim | Preenchido pelo servidor a cada alteração. |
| `criadoPor` | ObjectId (referência a `usuarios._id`) | Sim | Usuário que realizou o cadastro (o próprio paciente, em caso de autocadastro, ou a secretaria). Apoia a auditoria. |

**Estrutura do objeto `preferenciasNotificacao`:**

| Nome | Tipo | Obrigatoriedade | Exemplos/Comentários |
|------|------|-----------------|----------------------|
| `preferenciasNotificacao.receberLembretes` | Booleano | Sim | `true` por padrão. Indica se o paciente aceita lembretes e confirmações antecipadas de consulta. |
| `preferenciasNotificacao.receberAvisosFila` | Booleano | Sim | `true` por padrão. Indica se o paciente aceita ser avisado quando uma vaga compatível for liberada. |
| `preferenciasNotificacao.canal` | Lista literal e estática de valores | Sim | No MVP: `"SISTEMA"` (notificação simulada no sistema) ou `"EMAIL"` (e-mail de teste). Os valores `"SMS"` e `"WHATSAPP"` serão acrescentados quando a integração real for implementada (evolução futura). |

**Índices:**

| Índice | Tipo | Finalidade |
|--------|------|------------|
| `{ cpf: 1 }` | Único | Impede duplicidade de cadastro da mesma pessoa e acelera a busca por CPF. |
| `{ usuarioId: 1 }` | Único, parcial (somente quando `usuarioId` existe) | Garante no máximo um paciente por usuário e permite localizar o paciente a partir do login. |
| `{ nome: 1 }` | Comum | Busca e listagem por nome na tela da secretaria. |

**Exemplo de documento:**

```json
{
  "_id": { "$oid": "6720b2a4c2b4d5e6f7a8ba01" },
  "usuarioId": { "$oid": "6720a9d0c2b4d5e6f7a8b9f1" },
  "nome": "João Pedro da Silva",
  "cpf": "12345678909",
  "dataNascimento": { "$date": "1988-04-17T00:00:00Z" },
  "telefone": "+5519999998888",
  "email": "joao.silva@email.com",
  "preferenciasNotificacao": {
    "receberLembretes": true,
    "receberAvisosFila": true,
    "canal": "EMAIL"
  },
  "ativo": true,
  "criadoEm": { "$date": "2026-10-12T15:30:00Z" },
  "atualizadoEm": { "$date": "2026-10-12T15:30:00Z" },
  "criadoPor": { "$oid": "6720a9d0c2b4d5e6f7a8b9f1" }
}
```

**Regras de negócio e integridade:**

- **Permissões de leitura e escrita:**
  - `PACIENTE`: lê e altera somente o **próprio** registro (identificado por `usuarioId`).
  - `MEDICO`: lê apenas os **dados básicos** (`nome`, `dataNascimento`, `telefone`) dos pacientes com consultas em sua agenda. Não altera cadastros.
  - `SECRETARIA_ADMIN`: cria, edita, lista, consulta e desativa qualquer paciente.
- CPF e data de nascimento só podem ser corrigidos pela `SECRETARIA_ADMIN`, e cada correção é registrada em `historico`.
- As notificações **não devem expor dados sensíveis** (ex.: o aviso informa data, horário, médico e unidade, mas não o motivo do atendimento).
- Taxas de faltas e de cancelamentos por paciente **não são armazenadas** nesta coleção. São calculadas a partir de `consultas` e `historico`, evitando dados duplicados e desatualizados.
- A desativação (`ativo = false`) não remove o paciente da fila de espera de forma automática: a API deve cancelar os registros ativos em `filaEspera` e registrar a ação no histórico.

---

## Script de inicialização (mongosh)

Trecho a ser incorporado ao script de carga do projeto. Cria as coleções com validação de esquema (`$jsonSchema`) e os índices descritos acima.

```javascript
db.createCollection("usuarios", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["nome", "email", "senha", "perfil", "ativo", "criadoEm", "atualizadoEm"],
      additionalProperties: false,
      properties: {
        _id: { bsonType: "objectId" },
        nome: { bsonType: "string", minLength: 3, maxLength: 120 },
        email: { bsonType: "string", pattern: "^[^\\s@A-Z]+@[^\\s@A-Z]+\\.[^\\s@A-Z]+$" },
        senha: {
          bsonType: "object",
          required: ["hash", "salt", "algoritmo", "iteracoes"],
          additionalProperties: false,
          properties: {
            hash: { bsonType: "string" },
            salt: { bsonType: "string" },
            algoritmo: { bsonType: "string" },
            iteracoes: { bsonType: "int", minimum: 1 }
          }
        },
        perfil: { enum: ["PACIENTE", "MEDICO", "SECRETARIA_ADMIN"] },
        ativo: { bsonType: "bool" },
        ultimoAcessoEm: { bsonType: "date" },
        criadoEm: { bsonType: "date" },
        atualizadoEm: { bsonType: "date" }
      }
    }
  }
});

db.usuarios.createIndex({ email: 1 }, { unique: true });
db.usuarios.createIndex({ perfil: 1, ativo: 1 });

db.createCollection("pacientes", {
  validator: {
    $jsonSchema: {
      bsonType: "object",
      required: ["nome", "cpf", "dataNascimento", "telefone", "email",
                 "preferenciasNotificacao", "ativo", "criadoEm", "atualizadoEm", "criadoPor"],
      additionalProperties: false,
      properties: {
        _id: { bsonType: "objectId" },
        usuarioId: { bsonType: "objectId" },
        nome: { bsonType: "string", minLength: 3, maxLength: 120 },
        cpf: { bsonType: "string", pattern: "^[0-9]{11}$" },
        dataNascimento: { bsonType: "date" },
        telefone: { bsonType: "string", pattern: "^\\+[0-9]{10,15}$" },
        email: { bsonType: "string", pattern: "^[^\\s@A-Z]+@[^\\s@A-Z]+\\.[^\\s@A-Z]+$" },
        preferenciasNotificacao: {
          bsonType: "object",
          required: ["receberLembretes", "receberAvisosFila", "canal"],
          additionalProperties: false,
          properties: {
            receberLembretes: { bsonType: "bool" },
            receberAvisosFila: { bsonType: "bool" },
            canal: { enum: ["SISTEMA", "EMAIL"] }
          }
        },
        ativo: { bsonType: "bool" },
        criadoEm: { bsonType: "date" },
        atualizadoEm: { bsonType: "date" },
        criadoPor: { bsonType: "objectId" }
      }
    }
  }
});

db.pacientes.createIndex({ cpf: 1 }, { unique: true });
db.pacientes.createIndex(
  { usuarioId: 1 },
  { unique: true, partialFilterExpression: { usuarioId: { $exists: true } } }
);
db.pacientes.createIndex({ nome: 1 });
```