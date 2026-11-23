/*
Autoria: Gabriel Martins de Almeida
RA: 25006162
*/

import { useCallback, useEffect, useState } from 'react';
import { useBlocker, useSearchParams } from 'react-router';
import BloqueiosCard from '../components/agenda/BloqueiosCard';
import JornadaForm from '../components/agenda/JornadaForm';
import SeletorMedico from '../components/agenda/SeletorMedico';
import { useAuth } from '../hooks/useAuth';
import { useRecords } from '../hooks/useRecords';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import Modal from '../components/ui/Modal';
import PageHeader from '../components/ui/PageHeader';

export default function AgendaPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [jornadaAlterada, setJornadaAlterada] = useState(false);
  const [bloqueioAlterado, setBloqueioAlterado] = useState(false);
  const { usuario } = useAuth();
  const agendaPropria = usuario.perfil === 'MEDICO';
  const medicoId = agendaPropria ? 'meu' : searchParams.get('medicoId') ?? '';
  const medicos = useRecords(agendaPropria ? null : 'medicos');
  const ativos = medicos.records.filter((medico) => medico.ativo !== false);
  const indisponivel = !agendaPropria && !medicos.loading && !medicos.error && medicoId
    && !ativos.some((medico) => (medico.id || medico._id) === medicoId);
  const alterada = jornadaAlterada || bloqueioAlterado;
  const navegacao = useBlocker(alterada);
  const calendario = agendaPropria ? '/calendario' : `/calendario?medicoId=${encodeURIComponent(medicoId)}`;
  const informarJornadaAlterada = useCallback((valor) => setJornadaAlterada(valor), []);
  const informarBloqueioAlterado = useCallback((valor) => setBloqueioAlterado(valor), []);

  useEffect(() => {
    if (!alterada) return;
    const avisar = (event) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', avisar);
    return () => window.removeEventListener('beforeunload', avisar);
  }, [alterada]);

  function descartarAlteracoes() {
    setJornadaAlterada(false);
    setBloqueioAlterado(false);
    navegacao.proceed();
  }

  function escolherMedico(novoId) {
    if (novoId !== medicoId) setSearchParams(novoId ? { medicoId: novoId } : {});
  }

  return (
    <div className="page">
      <PageHeader title={agendaPropria ? 'Minha agenda' : 'Agenda'} description="Configure os dias e horários de atendimento, a duração das consultas e os períodos bloqueados de cada médico." />

      {!agendaPropria && (
        <SeletorMedico medicoId={medicoId} medicos={medicos} onChange={escolherMedico} />
      )}

      {medicoId && !indisponivel && <Card className="agenda-orientacao">
        <ol className="agenda-passos" aria-label="Como configurar a agenda">
          <li>Defina a jornada semanal e a duração das consultas.</li>
          <li>Adicione bloqueios para folgas, férias ou outros períodos sem atendimento.</li>
        </ol>
        <Button to={calendario} variant="outline">Ver horários no calendário</Button>
        <p className="field__hint">No calendário, consulte os horários livres, as consultas marcadas e os períodos bloqueados.</p>
      </Card>}

      {medicoId && !indisponivel ? (
        <div className="agenda-grid">
          <JornadaForm key={`jornada-${medicoId}`} medicoId={medicoId} onDirtyChange={informarJornadaAlterada} />
          <BloqueiosCard key={`bloqueios-${medicoId}`} medicoId={medicoId} onDirtyChange={informarBloqueioAlterado} />
        </div>
      ) : (
        !medicoId && !medicos.loading && !medicos.error && ativos.length > 0 &&
          <Card><p className="empty-state" role="status">Selecione um médico para configurar a agenda.</p></Card>
      )}
      {navegacao.state === 'blocked' && (
        <Modal title="Sair sem salvar as alterações?" description="Há alterações na jornada ou no novo bloqueio que ainda não foram salvas." onClose={navegacao.reset}>
          <p>Continue editando para salvar ou descarte as alterações para prosseguir.</p>
          <footer className="form-actions">
            <Button variant="outline" onClick={navegacao.reset}>Continuar editando</Button>
            <Button variant="danger" onClick={descartarAlteracoes}>Descartar e continuar</Button>
          </footer>
        </Modal>
      )}
    </div>
  );
}
