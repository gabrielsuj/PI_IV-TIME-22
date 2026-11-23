const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const codigo = readFileSync(require('node:path').join(__dirname, '../verificar-modelagem.js'), 'utf8');

function executar({ total = 11, recusas = [121, 11000, 121, 11000, 121, 11000], vencedor = true } = {}) {
  let proxima = 0;
  let resultado;
  const indice = { stage: 'IXSCAN', indexName: 'ix_bloqueio_medico_periodo' };
  const recusar = () => {
    const code = recusas[proxima++];
    if (code !== null) throw Object.assign(new Error('recusado'), { code });
  };
  const db = {
    medicos: { countDocuments: (filtro) => filtro ? 2 : total, insertOne: recusar },
    bloqueios: { countDocuments: () => 1, insertOne: recusar,
      find: () => ({ explain: () => ({ queryPlanner: {
        winningPlan: vencedor ? { inputStage: indice } : { stage: 'COLLSCAN' }, rejectedPlans: [indice],
      } }) }) },
    agendas: { insertOne: recusar }, especialidades: { insertOne: recusar },
  };
  vm.runInNewContext(codigo, { db, ObjectId: (id) => id || 'novo', print() {}, quit: (exit) => { resultado = exit; } });
  assert.equal(proxima, 6);
  return resultado;
}

test('carga válida termina com zero', () => assert.equal(executar(), 0));
test('divergência nos dados termina com erro', () => assert.equal(executar({ total: 10 }), 1));
test('índice apenas no plano rejeitado não passa', () => assert.equal(executar({ vencedor: false }), 1));
test('exceção com código diferente do esperado não passa', () => assert.equal(executar({ recusas: [121, 121, 121, 11000, 121, 11000] }), 1));
test('falha de operação não é confundida com validação', () => assert.equal(executar({ recusas: [13, 11000, 121, 11000, 121, 11000] }), 1));
test('inserção indevida faz a verificação falhar', () => assert.equal(executar({ recusas: [null, 11000, 121, 11000, 121, 11000] }), 1));
