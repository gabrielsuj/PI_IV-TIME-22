import ApiStatus from '../components/ApiStatus';
import PageHeader from '../components/ui/PageHeader';

export default function StatusPage() {
  return (
    <div className="page">
      <PageHeader title="Status do sistema" description="Resultado da verificação do endpoint de saúde da API." />

      <ApiStatus detailed />
    </div>
  );
}
