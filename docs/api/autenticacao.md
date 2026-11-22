# API de autenticação e disponibilidade

Contratos de `AuthHandler`, `AuthService`, `Autenticador` e `HealthHandler`. A URL local, com o `.env.example`, é `http://localhost:3000`.

## Rotas

| Método e caminho | Entrada | Resposta |
|---|---|---|
| `GET /health` | Sem autenticação | `200 {"status":"ok"}` |
| `POST /auth/login` | JSON com `email` e `senha` | `200` com `token`, `expiraEm` e `usuario` |
| `GET /auth/me` | `Authorization: Bearer <token>` | `200` com `id`, `nome` e `perfil` da sessão |
| `POST /auth/logout` | Token da sessão, quando houver | `204`, sem corpo; encerra o token informado |

`/health` confirma que o servidor HTTP responde. A implementação não executa um novo ping no MongoDB nessa requisição.

## Login e uso da sessão

```bash
curl -X POST http://localhost:3000/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"secretaria@consultafacil.demo","senha":"Demo@123"}'
```

Exemplo de resposta; os valores de token, usuário e expiração variam:

```json
{
  "token": "token-retornado-pelo-servidor",
  "expiraEm": "2026-11-21T15:00:00Z",
  "usuario": {
    "id": "000000000000000000000001",
    "nome": "Secretaria",
    "perfil": "SECRETARIA_ADMIN"
  }
}
```

Copie o token retornado para as chamadas autenticadas:

```bash
curl http://localhost:3000/auth/me -H 'Authorization: Bearer <token>'
curl -X POST http://localhost:3000/auth/logout -H 'Authorization: Bearer <token>'
```

O e-mail é normalizado para minúsculas e sem espaços nas extremidades. E-mail e senha são obrigatórios. O serviço verifica a senha e o estado ativo do usuário antes de abrir a sessão; a senha e seu hash não aparecem na resposta.

As sessões ficam em memória e expiram após `SESSION_TTL_MINUTES` (padrão 60). Reiniciar a API invalida as sessões existentes. O token identifica a sessão; os endpoints de negócio ainda validam o perfil e, quando aplicável, o vínculo do paciente ou médico. Os perfis são `PACIENTE`, `MEDICO` e `SECRETARIA_ADMIN`.

## Erros

| Código | Situação |
|---|---|
| `400` | JSON inválido ou ausência de e-mail/senha |
| `401` | E-mail não cadastrado, senha incorreta, usuário desativado ou sessão inválida/expirada |
| `403` | Sessão válida, mas perfil não autorizado em uma rota de negócio |

Essas rotas usam o formato `{"status":401,"erro":"mensagem"}`. O logout é idempotente: sem token válido também retorna `204`.

Não há rota de autocadastro ou manutenção de usuários no MVP. As contas de demonstração são criadas pelos [seeds](../modelagem/carga-demonstracao.md); o cadastro de um paciente pela secretaria não cria uma conta de login.

## Verificação

Na pasta `backend`, `./mvnw test -Dtest=AuthHandlerTest,RotasAutorizacaoTest,SenhaHasherTest,SessoesTest` cobre login, sessão, expiração, logout, hash e autorização. A modelagem está em [usuarios-pacientes.md](../modelagem/usuarios-pacientes.md).

Esses testes usam usuários em memória. O `AcessoPacientesMongoIntegrationTest` (#50) repete o fluxo com a coleção `usuarios` do MongoDB, gravada pelo validador `$jsonSchema`, e com a mesma montagem de rotas do `App.java`:

| Cenário | Resultado conferido |
|---|---|
| Login válido | `200` com `token`, `expiraEm` (= agora + `SESSION_TTL_MINUTES`) e `usuario`; `ultimoAcessoEm` gravado no Mongo |
| E-mail com maiúsculas ou espaços | Normalizado antes da busca; o login funciona |
| Senha errada, e-mail inexistente, usuário inativo | `401` com a mensagem de cada caso; o inativo não registra acesso |
| Dois logins do mesmo usuário | Tokens diferentes; o logout de um não derruba o outro |
| Logout e depois `GET`/`POST /pacientes` | `401` e nada gravado |
| Sessão vencida no meio do cadastro | `401` sem gravar; um novo login continua o cadastro. Com 59 minutos a sessão ainda vale |
| Sem token ou com token inventado | `401` em `/pacientes` e `/auth/me`; `/health` continua público |

Comando: `./mvnw test -Dtest='AcessoPacientesMongoIntegrationTest*'` (requer MongoDB com `consultafacil_test` e o [servidor de CPF](../testes/estrategia-de-testes.md#preparar-o-servidor-tcp-de-cpf)).
