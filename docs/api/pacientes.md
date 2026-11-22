# API de pacientes

Contrato de `PacienteHandler`, `PacienteService` e `PacienteValidator`. Todas as chamadas exigem `Authorization: Bearer <token>` obtido no [login](autenticacao.md).

## Rotas e permissões

| Método e caminho | Resultado | Perfis |
|---|---|---|
| `GET /pacientes?busca=<texto>` | `200`, array de pacientes | `MEDICO`, `SECRETARIA_ADMIN` |
| `GET /pacientes/{id}` | `200`, paciente | `MEDICO`, `SECRETARIA_ADMIN` |
| `POST /pacientes` | `201`, paciente e cabeçalho `Location: /pacientes/{id}` | `SECRETARIA_ADMIN` |
| `PUT /pacientes/{id}` | `200`, paciente atualizado | `SECRETARIA_ADMIN` |

O perfil `PACIENTE` não acessa esse cadastro. A leitura do médico abrange o cadastro de pacientes; não é filtrada por consultas com esse médico. Não há `DELETE`, desativação ou autocadastro de pacientes nessa API.

A coleção também aceita `/pacientes/`. A listagem vazia retorna `[]`. A busca é parcial por nome, e-mail ou CPF; nome e e-mail ignoram maiúsculas, e o CPF pode ser pesquisado com ou sem máscara. Caracteres de expressão regular são tratados como texto. Sem `busca`, retorna a lista completa, ordenada por nome e ID, sem paginação.

## Entrada e resposta

POST e PUT recebem o objeto completo com três textos obrigatórios:

```json
{
  "nome": "Paulo Paciente",
  "cpf": "529.982.247-25",
  "email": "paulo.paciente@example.com"
}
```

Exemplo de resposta com ID ilustrativo:

```json
{
  "id": "100000000000000000000001",
  "nome": "Paulo Paciente",
  "cpf": "52998224725",
  "email": "paulo.paciente@example.com"
}
```

Os espaços nas extremidades são removidos; o CPF é armazenado sem máscara e o e-mail em minúsculas. O CPF deve conter 11 dígitos ou a máscara `000.000.000-00`, ter dígitos verificadores válidos e não repetir o mesmo dígito. O e-mail passa pela validação de formato, até 254 caracteres e parte local até 64. CPF e e-mail são únicos entre pacientes, inclusive sob gravações concorrentes pelos índices do banco.

Em POST e PUT, o backend normaliza o CPF e consulta o servidor TCP para verificar os dígitos. Se o validador estiver indisponível, a escrita retorna 503 e o cadastro é preservado. A configuração e os comandos para iniciar o processo estão no [guia do backend](../../README.md#4-executar-o-backend).

O PUT substitui `nome`, `cpf` e `email`; os três continuam obrigatórios. Campos extras existentes no documento, como `usuarioId` da carga de demonstração, são preservados pela atualização com `$set`. O endpoint não cria nem altera esse vínculo de login.

O corpo deve ser um único objeto JSON de até 16 KiB. Os três campos lidos devem ser textos; propriedades adicionais não são utilizadas pelo handler e não devem ser enviadas como campos de cadastro.

## Erros

| Código | Situação |
|---|---|
| `400` | Campo inválido/ausente, JSON inválido, corpo excedido, ID malformado ou CPF/e-mail duplicado |
| `401` | Sessão ausente, inválida ou expirada |
| `403` | Perfil sem permissão de leitura/escrita |
| `404` | Paciente inexistente ou caminho não reconhecido |
| `405` | Método não permitido; cabeçalho `Allow` informa `GET, POST` ou `GET, PUT` |
| `500` | Falha interna ao processar a solicitação |
| `503` | Validador de CPF temporariamente indisponível |

Os erros produzidos pelo cadastro usam `{"mensagem":"texto"}`. A autorização, aplicada antes do handler, usa o padrão geral `{"status":403,"erro":"texto"}`. O cliente deve aceitar ambos os formatos.

## Modelagem e verificação

O schema, os índices `uk_paciente_cpf` e `uk_paciente_email` e o vínculo opcional de login estão em [usuarios-pacientes.md](../modelagem/usuarios-pacientes.md) e em [`04-esquema-pacientes.js`](../../database/init/04-esquema-pacientes.js).

Na pasta `backend`, `./mvnw test -Dtest=PacienteValidatorTest,PacienteServiceTest,PacienteHandlerTest,RotasAutorizacaoTest,ValidadorCpfTcpTest` verifica validação, unicidade, consulta/edição, contratos HTTP, acesso por perfil e o validador TCP. Prepare o [servidor de CPF para os testes](../testes/estrategia-de-testes.md#preparar-o-servidor-tcp-de-cpf). Na pasta `frontend`, `npm test -- src/__tests__/UxPacientes.test.jsx src/__tests__/Registration.test.jsx` cobre busca, detalhe, edição e preservação dos dados em erros.
