import { useEffect, useState } from 'react';
import FiltrosAgendamento from '../components/agendamento/FiltrosAgendamento';
import Button from '../components/ui/Button';
import PageHeader from '../components/ui/PageHeader';
import { buscarCatalogosAgendamento } from '../services/catalogoAgendamentoService';

export default function NovaConsultaPage() {
  const [catalogos, setCatalogos] = useState({ especialidades: [], medicos: [] });
  const [filtros, setFiltros] = useState({ especialidadeId: '', medicoId: '', data: '' });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    buscarCatalogosAgendamento({ signal: controller.signal })
      .then((data) => {
        if (controller.signal.aborted) return;
        setCatalogos(data);
        setFiltros((current) => {
          const especialidadeValida = data.especialidades.some((especialidade) => especialidade.id === current.especialidadeId);
          const medicoValido = especialidadeValida && data.medicos.some((medico) => medico.id === current.medicoId
            && medico.especialidadeIds.includes(current.especialidadeId));
          return { ...current, especialidadeId: especialidadeValida ? current.especialidadeId : '', medicoId: medicoValido ? current.medicoId : '' };
        });
        setError(null);
      })
      .catch((failure) => { if (!controller.signal.aborted) setError(failure); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [revision]);

  function retry() {
    setLoading(true);
    setError(null);
    setRevision((current) => current + 1);
  }

  return (
    <div className="page">
      <PageHeader title="Nova consulta" description="Escolha a especialidade, o médico e a data para o atendimento."
        action={<Button to="/" variant="outline">Voltar ao início</Button>} />
      <FiltrosAgendamento {...catalogos} value={filtros} onChange={setFiltros} loading={loading} error={error} onRetry={retry} />
      {/* Necessita issue #21 para buscar vagas e confirmar o agendamento. */}
    </div>
  );
}
