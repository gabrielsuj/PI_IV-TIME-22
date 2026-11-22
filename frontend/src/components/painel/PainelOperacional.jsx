/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import { useSearchParams } from 'react-router';
import { useAuth } from '../../hooks/useAuth';
import { FUSO_CLINICA } from '../../utils/consulta';
import { dataPainelValida, erroPeriodoPainel } from '../../utils/painel';
import Alert from '../ui/Alert';
import Button from '../ui/Button';
import Card from '../ui/Card';
import Field from '../ui/Field';
import AgendaDiaPainel from './AgendaDiaPainel';
import IndicadoresPainel from './IndicadoresPainel';
import ProximasConsultasPainel from './ProximasConsultasPainel';

export default function PainelOperacional({ hoje, medicos, medicoSelecionado, verificandoMedico }) {
  const { usuario } = useAuth();
  const secretaria = usuario?.perfil === 'SECRETARIA_ADMIN';
  const permitido = secretaria || usuario?.perfil === 'MEDICO';
  const [params, setParams] = useSearchParams();
  if (!permitido) return null;

  // O filtro de médico é compartilhado pelas seções; no perfil médico, o próprio
  // vínculo continua sendo resolvido pelo servidor.
  const selecionado = secretaria ? (params.get('medicoId') ?? '').toLowerCase() : '';
  const medicoValido = !selecionado || /^[a-f\d]{24}$/i.test(selecionado);
  const medicoId = selecionado.toLowerCase() || undefined;
  const de = params.get('de') ?? hoje;
  const ate = params.get('ate') ?? hoje;
  const dia = params.get('dia') ?? hoje;
  const temFiltros = Boolean(selecionado || de !== hoje || ate !== hoje || dia !== hoje);
  const erroPeriodo = erroPeriodoPainel(de, ate);
  const erroDia = !dataPainelValida(dia) ? 'Informe uma data válida para a agenda.' : null;
  const escopo = secretaria ? medicoId ? medicoSelecionado?.nome || 'Médico selecionado' : 'Todos os médicos' : 'Seus atendimentos';

  const filtrar = (campo, valor) => {
    const proximos = new URLSearchParams(params);
    if (campo === 'medicoId' && !valor) proximos.delete(campo);
    else proximos.set(campo, valor);
    setParams(proximos);
  };

  const limparFiltros = () => {
    const proximos = new URLSearchParams(params);
    for (const campo of ['medicoId', 'de', 'ate', 'dia']) proximos.delete(campo);
    setParams(proximos);
  };

  return <>
    <Card title="Filtros do painel" className="painel-filtros painel-consultas"
      description={`Datas no fuso da clínica (${FUSO_CLINICA}).`}>
      <div className="painel-filtros__grade">
        {secretaria && <Field as="select" label="Médico do painel" value={selecionado}
          disabled={medicos.loading || Boolean(medicos.error)} error={!medicoValido ? 'Informe um médico válido.' : undefined}
          hint="Aplica-se aos indicadores e às consultas."
          onChange={(event) => filtrar('medicoId', event.target.value)}>
          <option value="">{medicos.loading ? 'Carregando médicos…' : 'Todos os médicos'}</option>
          {selecionado && !medicoSelecionado && <option value={selecionado}>Médico selecionado</option>}
          {medicos.records.map((medico) => <option key={medico.id || medico._id} value={medico.id || medico._id}>
            {medico.nome}{medico.ativo === false ? ' (inativo)' : ''}
          </option>)}
        </Field>}
        <fieldset className="painel-filtros__periodo">
          <legend className="sr-only">Período dos indicadores</legend>
          <Field type="date" label="Início dos indicadores" value={de} min="0001-01-01" max="9999-12-31"
            onChange={(event) => filtrar('de', event.target.value)} />
          <Field type="date" label="Fim dos indicadores" value={ate} min="0001-01-01" max="9999-12-31" error={erroPeriodo}
            hint="Até 62 dias, contando o início e o fim." onChange={(event) => filtrar('ate', event.target.value)} />
        </fieldset>
        <Field type="date" label="Dia da agenda" value={dia} min="0001-01-01" max="9999-12-31" error={erroDia}
          onChange={(event) => filtrar('dia', event.target.value)} />
      </div>
      {secretaria && medicos.error && <Alert action={<Button variant="outline" onClick={medicos.refresh}>Tentar carregar médicos novamente</Button>}>
        Não foi possível carregar os médicos. {medicos.error.message}
      </Alert>}
      {secretaria && medicos.loading && <p role="status">Carregando lista de médicos…</p>}
      {secretaria && !medicos.loading && !medicos.error && medicos.records.length === 0 && <p role="status">Nenhum médico cadastrado.</p>}
      <div className="painel-filtros__rodape">
        <p className="page__lead" role="status" aria-atomic="true">Escopo do painel: {escopo}.</p>
        {temFiltros && <Button variant="ghost" onClick={limparFiltros}>Voltar aos filtros de hoje</Button>}
      </div>
    </Card>
    {!medicoValido ? <Alert>O filtro de médico é inválido. Selecione um médico ou todos os médicos.</Alert> : <>
      {!erroPeriodo && <IndicadoresPainel de={de} ate={ate} medicoId={medicoId} />}
      {!erroDia && <AgendaDiaPainel dia={dia} medicoId={medicoId} medicoInativo={medicoSelecionado?.ativo === false} verificandoMedico={verificandoMedico} />}
      <ProximasConsultasPainel medicoId={medicoId} />
    </>}
  </>;
}
