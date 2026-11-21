/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import ApiStatus from '../components/ApiStatus';
import PageHeader from '../components/ui/PageHeader';

// Reutiliza a verificação de saúde com os detalhes de conexão necessários à consulta
// técnica.
export default function StatusPage() {
  return (
    <div className="page">
      <PageHeader title="Status do sistema" description="Resultado da verificação do endpoint de saúde da API." />

      <ApiStatus detailed />
    </div>
  );
}
