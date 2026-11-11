import Button from '../components/ui/Button';
import PageHeader from '../components/ui/PageHeader';

export default function AccessDeniedPage() {
  return (
    <div className="page">
      <PageHeader title="Acesso não permitido" description="Seu perfil não tem acesso a esta tela. Se precisar dessas informações, solicite à secretaria." />
      <div><Button to="/">Voltar ao início</Button></div>
    </div>
  );
}
