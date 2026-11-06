// Cria as coleções da aplicação no banco atual.
// No Docker, o banco é o de MONGO_INITDB_DATABASE (docker-compose.yml);
// com scripts/recriar-banco.sh, é o banco passado como parâmetro.
const colecoes = [
  'usuarios', 'pacientes', 'medicos', 'especialidades', 'agendas', 'bloqueios',
  'consultas', 'filaEspera', 'notificacoes', 'historico'
];

const existentes = db.getCollectionNames();
colecoes.filter(c => !existentes.includes(c)).forEach(c => db.createCollection(c));
