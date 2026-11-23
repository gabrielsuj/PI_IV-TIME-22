/*
Autoria: Vinicius Valim de Vechi Cardoso
RA: 25000387
*/

import Button from '../components/ui/Button';
import PageHeader from '../components/ui/PageHeader';

// O retorno ao início permite retomar a navegação quando o endereço solicitado não
// corresponde a uma tela.
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
