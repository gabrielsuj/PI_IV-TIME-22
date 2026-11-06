import Badge from '../components/ui/Badge';
import Card from '../components/ui/Card';
import PageHeader from '../components/ui/PageHeader';

const PROFILES = [
  { name: 'Secretaria / Administrador', tone: 'teal', description: 'Organização da rotina da clínica.', permissions: ['Gerenciar cadastros de pacientes e médicos', 'Organizar agendas e consultas', 'Acompanhar cancelamentos e fila de espera'] },
  { name: 'Médico', tone: 'info', description: 'Acompanhamento dos próprios atendimentos.', permissions: ['Consultar a própria agenda', 'Acompanhar as consultas', 'Atualizar o status dos atendimentos'] },
  { name: 'Paciente', tone: 'success', description: 'Acesso às próprias consultas.', permissions: ['Consultar disponibilidade e agendar', 'Cancelar as próprias consultas', 'Registrar interesse na fila de espera'] },
];

export default function ProfilesPage() {
  return (
    <div className="page">
      <PageHeader title="Perfis de acesso" description="Conheça as funções disponíveis para cada perfil no ConsultaFácil." />
      <div className="record-grid">
        {PROFILES.map((profile) => (
          <Card key={profile.name} title={profile.name} description={profile.description}>
            <Badge tone={profile.tone}>{profile.name}</Badge>
            <ul className="permissions-list">{profile.permissions.map((permission) => <li key={permission}>{permission}</li>)}</ul>
          </Card>
        ))}
      </div>
    </div>
  );
}
