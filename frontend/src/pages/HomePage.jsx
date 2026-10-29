import ApiStatus from '../components/ApiStatus';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import PageHeader from '../components/ui/PageHeader';

export default function HomePage() {
  return (
    <div className="page">
      <PageHeader title="ConsultaFácil" description="Centralize os cadastros e organize a rotina de atendimento da clínica." />
      <div className="record-grid">
        <Card title="Pacientes" description="Informações de identificação e contato."><Button to="/pacientes" variant="outline">Gerenciar pacientes</Button></Card>
        <Card title="Médicos" description="Profissionais e áreas de atendimento."><Button to="/medicos" variant="outline">Gerenciar médicos</Button></Card>
        <Card title="Especialidades" description="Áreas de atendimento da clínica."><Button to="/especialidades" variant="outline">Gerenciar especialidades</Button></Card>
      </div>

      <ApiStatus />

      <div><Button to="/status" variant="ghost">Ver detalhes da conexão</Button></div>
    </div>
  );
}
