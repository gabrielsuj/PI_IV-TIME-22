import Button from '../components/ui/Button';
import PageHeader from '../components/ui/PageHeader';

export default function NotFoundPage() {
  return (
    <div className="page">
      <PageHeader title="Página não encontrada" description="O endereço acessado não existe ou foi alterado." />
      <div><Button to="/">
        Voltar ao início
      </Button></div>
    </div>
  );
}
