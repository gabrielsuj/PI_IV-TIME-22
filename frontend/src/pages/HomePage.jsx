import ApiStatus from '../components/ApiStatus';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import PageHeader from '../components/ui/PageHeader';
import { podeAbrir } from '../data/menu';
import { useAuth } from '../hooks/useAuth';

const SHORTCUTS = [
  { to: '/pacientes', title: 'Pacientes', description: 'Informações de identificação e contato.', resource: 'pacientes' },
  { to: '/medicos', title: 'Médicos', description: 'Profissionais e áreas de atendimento.', resource: 'médicos' },
  { to: '/especialidades', title: 'Especialidades', description: 'Áreas de atendimento da clínica.', resource: 'especialidades' },
];

export default function HomePage() {
  const { usuario } = useAuth();
  const shortcuts = SHORTCUTS.filter(({ to }) => podeAbrir(to, usuario?.perfil));
  const action = usuario?.perfil === 'SECRETARIA_ADMIN' ? 'Gerenciar' : 'Consultar';
  return (
    <div className="page">
      <PageHeader title="ConsultaFácil" description="Centralize os cadastros e organize a rotina de atendimento da clínica."
        action={podeAbrir('/nova-consulta', usuario?.perfil) && <Button to="/nova-consulta">Nova consulta</Button>} />
      {shortcuts.length > 0 && <div className="record-grid">
        {shortcuts.map(({ to, title, description, resource }) => (
          <Card key={to} title={title} description={description}><Button to={to} variant="outline">{action} {resource}</Button></Card>
        ))}
      </div>}

      <ApiStatus />

      {podeAbrir('/status', usuario?.perfil) && <div><Button to="/status" variant="ghost">Ver detalhes da conexão</Button></div>}
    </div>
  );
}
