import { useState } from 'react';
import { useLocation } from 'react-router';
import ApiStatus from '../components/ApiStatus';
import PainelOperacional from '../components/painel/PainelOperacional';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import PageHeader from '../components/ui/PageHeader';
import { podeAbrir } from '../data/menu';
import { useAuth } from '../hooks/useAuth';
import { agoraNoFuso } from '../utils/calendario';
import { atalhosPainel, origemPainel } from '../utils/painel';

const SHORTCUTS = [
  { to: '/pacientes', title: 'Pacientes', description: 'Informações de identificação e contato.', resource: 'pacientes' },
  { to: '/medicos', title: 'Médicos', description: 'Profissionais e áreas de atendimento.', resource: 'médicos' },
  { to: '/especialidades', title: 'Especialidades', description: 'Áreas de atendimento da clínica.', resource: 'especialidades' },
];

const OPERATIONAL_SHORTCUTS = [
  { to: '/calendario', title: 'Calendário', description: 'Visualize os horários e os atendimentos no fuso da agenda do médico.', label: 'Abrir calendário' },
  { to: '/consultas', title: 'Consultas', description: 'Acompanhe as consultas e abra os detalhes dos atendimentos.', label: 'Ver consultas' },
];

const PATIENT_SHORTCUTS = [
  { to: '/consultas', title: 'Minhas consultas', description: 'Acompanhe seus agendamentos e os detalhes de cada consulta.', label: 'Ver minhas consultas' },
  { to: '/avisos-vaga', title: 'Avisos de vaga', description: 'Confira as vagas disponíveis para suas inscrições na fila de espera.', label: 'Ver avisos de vaga' },
  { to: '/notificacoes', title: 'Notificações', description: 'Consulte os avisos sobre seus atendimentos.', label: 'Ver notificações' },
];

export default function HomePage() {
  const { usuario } = useAuth();
  const location = useLocation();
  const [hoje] = useState(() => agoraNoFuso().slice(0, 10));
  const perfil = usuario?.perfil;
  const operacional = perfil === 'SECRETARIA_ADMIN' || perfil === 'MEDICO';
  const destinos = atalhosPainel(location.search, perfil, hoje);
  const origem = origemPainel(location, usuario);
  const acessos = (operacional ? OPERATIONAL_SHORTCUTS : PATIENT_SHORTCUTS).filter(({ to }) => podeAbrir(to, perfil));
  const shortcuts = SHORTCUTS.filter(({ to }) => podeAbrir(to, perfil));
  const action = perfil === 'SECRETARIA_ADMIN' ? 'Gerenciar' : 'Consultar';
  const description = operacional
    ? perfil === 'MEDICO'
      ? 'Consulte sua agenda e acompanhe seus atendimentos.'
      : 'Organize a agenda e acompanhe os atendimentos da clínica.'
    : 'Agende consultas e acompanhe seus atendimentos e avisos de vaga.';

  return (
    <div className="page home-page">
      <PageHeader title={operacional ? 'Painel operacional' : 'Minha área'} description={description}
        action={podeAbrir('/nova-consulta', perfil) && <Button to="/nova-consulta" state={origem}>Nova consulta</Button>} />
      {operacional && <PainelOperacional hoje={hoje} />}
      <div className="record-grid">
        {acessos.map(({ to, title, description, label }) => (
          <Card key={to} title={title} description={description}>
            <Button to={operacional ? destinos[to === '/calendario' ? 'calendario' : 'consultas'] : to}
              disabled={operacional && !destinos[to === '/calendario' ? 'calendario' : 'consultas']}
              state={origem} variant="outline">{label}</Button>
          </Card>
        ))}
      </div>
      {shortcuts.length > 0 && <div className="record-grid">
        {shortcuts.map(({ to, title, description, resource }) => (
          <Card key={to} title={title} description={description}><Button to={to} variant="outline">{action} {resource}</Button></Card>
        ))}
      </div>}

      <ApiStatus />

      {podeAbrir('/status', perfil) && <div><Button to="/status" variant="ghost">Ver detalhes da conexão</Button></div>}
    </div>
  );
}
