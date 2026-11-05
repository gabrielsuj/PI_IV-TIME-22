db = db.getSiblingDB('consultafacil');

[ 'pacientes', 'medicos', 'especialidades', 'agendas', 'bloqueios', 'consultas', 'filaEspera', 'historico' ].forEach(c => db.createCollection(c));