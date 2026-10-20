db = db.getSiblingDB('consultafacil');

[ 'pacientes', 'medicos', 'especialidades', 'agendas', 'consultas', 'filaEspera', 'historico' ].forEach(c => db.createCollection(c));

//garante uma unica consulta ativa por medico + data + horario
db.consultas.createIndex(
    {medicoId:1, data: 1, horario: 1},
    {unique: true, name: 'uk_consulta_ativa', partialFilterExpression: { ativa: true } } 
)