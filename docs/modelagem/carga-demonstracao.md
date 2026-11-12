# Carga de demonstração

Dados fictícios carregados por `database/seed/` ao recriar um banco de desenvolvimento ou de demonstração:

```bash
bash scripts/recriar-banco.sh consultafacil_demo
docker exec -i consultafacil-mongo mongosh consultafacil_demo --quiet < scripts/verificar-carga.js
```

O banco de testes (`consultafacil_test`) não recebe seeds.

## Arquivos

Executados em ordem alfabética, cada um depende dos anteriores.

| Arquivo | Conteúdo |
|---|---|
| `01-agenda.js` | Clínica Geral, Cardiologia, Ana e Bruno, agendas e o bloqueio de 26/10 |
| `02-usuarios.js` | Logins da secretaria, da Ana e do paciente Paulo |
| `03-pacientes.js` | Paulo, Carla e Diego |
| `04-especialidades-medicos.js` | Mais 7 especialidades (Acupuntura desativada), 9 médicos (Helena inativa), 8 agendas e 5 bloqueios com datas relativas |
| `05-usuarios-equipe.js` | Login de cada médico ativo, uma segunda secretária (Lucas) e logins de Carla e Diego |
| `06-pacientes-ampliados.js` | 147 pacientes gerados com semente fixa, CPFs válidos e e-mails `@example.com` |
| `07-consultas-historico.js` | Consultas e histórico de 3 semanas antes a 3 semanas depois do dia da carga |

## Como as consultas são geradas

- **Dentro das regras da agenda:** apenas horários da jornada de cada médico, alinhados à duração, fora dos bloqueios, com especialidade entre as do médico.
- **Sem conflitos:** nenhum médico ou paciente com duas consultas ativas sobrepostas, e no máximo uma consulta por paciente no mesmo dia.
- **Ocupação realista:** cerca de 50% da agenda no passado e na próxima semana, 30% na segunda e 15% na terceira.
- **Status:**
  - Passadas: CONCLUIDA (cerca de 74%), FALTA (9%) e CANCELADA (17%).
  - Futuras: AGENDADA ou CONFIRMADA (confirmação mais frequente nos próximos 2 dias) e cerca de 10% CANCELADA.
- **Histórico igual ao da API:**
  - Cada consulta tem os eventos que `RegistroConsultaService` gravaria (CRIACAO, CONFIRMACAO, CONCLUSAO, FALTA ou CANCELAMENTO), com sequência 1..n, ator (perfil e id do usuário) e horários em ordem.
  - `versao` é igual à última sequência, sem eventos pendentes, e o status da consulta é sempre o do último evento.
  - Os motivos de cancelamento não contêm dados clínicos.
- **Paciente da demonstração:** Paulo (`paciente@consultafacil.demo`) tem duas consultas concluídas, uma cancelada, uma confirmada na próxima semana e uma agendada mais adiante, nunca em pediatria.
- **Repetível:**
  - A semente fixa gera sempre a mesma distribuição, relativa à data da carga.
  - O seed 07 apaga e recria só as consultas dele (IDs `70…`) e o histórico correspondente.
  - Consultas ativas criadas pela aplicação são preservadas e seus horários não são reutilizados.

## Verificação

`scripts/verificar-carga.js` confere, além dos validadores do banco:

- referências de paciente, médico e especialidade;
- horários dentro da jornada e fora dos bloqueios;
- ausência de sobreposição por médico e por paciente;
- integridade do histórico de cada consulta;
- vínculos de login.

Ao final, imprime as consultas da próxima semana por médico.

## Observações

- Datas e horários são gravados em UTC e interpretados no fuso `America/Sao_Paulo` (UTC−3).
- Todos os nomes, CPFs e e-mails são fictícios. Os registros originais (Ana, Bruno, Sofia, Paulo, Carla e Diego) mantêm o sufixo "— Fictício(a)" no nome.
