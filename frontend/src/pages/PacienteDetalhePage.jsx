import { useEffect, useRef, useState } from 'react';
import { useLocation, useParams } from 'react-router';
import RegistrationForm from '../components/RegistrationForm';
import Alert from '../components/ui/Alert';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import PageHeader from '../components/ui/PageHeader';
import { registrationSchemas } from '../data/registrationSchemas';
import { useAuth } from '../hooks/useAuth';
import { api } from '../services/api';
import { recuperarRascunho, removerRascunho } from '../services/rascunhoPacientes';

export default function PacienteDetalhePage() {
  const { id } = useParams();
  return <DetalhePaciente key={id} id={id} />;
}

function DetalhePaciente({ id }) {
  const { usuario } = useAuth();
  const podeEditar = usuario.perfil === 'SECRETARIA_ADMIN';
  const { pathname, search } = useLocation();
  const rota = pathname + search;
  const [editor, setEditor] = useState(() => podeEditar && recuperarRascunho(usuario.id, rota));
  const [paciente, setPaciente] = useState(null);
  const [erro, setErro] = useState(null);
  const [tentativa, setTentativa] = useState(0);
  const [aviso, setAviso] = useState('');
  const botaoEditar = useRef(null);

  useEffect(() => {
    const controller = new AbortController();
    api.get(`/pacientes/${encodeURIComponent(id)}`, { signal: controller.signal })
      .then((dados) => { if (!controller.signal.aborted) setPaciente(dados); })
      .catch((falha) => { if (!controller.signal.aborted) setErro(falha); });
    return () => controller.abort();
  }, [id, tentativa]);

  function fecharEditor() {
    removerRascunho(usuario.id, rota);
    setEditor(null);
  }

  function tentarNovamente() {
    setErro(null);
    setTentativa((valor) => valor + 1);
  }

  return (
    <div className="page">
      <PageHeader title="Detalhes do paciente" action={<Button to="/pacientes" variant="outline">Voltar à lista</Button>} />
      {aviso && <Alert tone="success">{aviso}</Alert>}
      {erro ? (
        <Alert action={erro.status !== 404 && <Button variant="outline" onClick={tentarNovamente}>Tentar novamente</Button>}>
          {erro.status === 404 ? 'Paciente não encontrado.' : `Não foi possível carregar o paciente. ${erro.message}`}
        </Alert>
      ) : paciente ? (
        <Card title={paciente.nome} action={podeEditar && <Button ref={botaoEditar} onClick={() => setEditor({})}>Editar paciente</Button>}>
          <dl className="resumo-agendamento">
            <div><dt>CPF</dt><dd>{paciente.cpf}</dd></div>
            <div><dt>E-mail</dt><dd>{paciente.email}</dd></div>
          </dl>
        </Card>
      ) : <p role="status">Carregando paciente…</p>}
      {podeEditar && paciente && editor && (
        <RegistrationForm resource="pacientes" schema={registrationSchemas.pacientes} record={paciente}
          initialValues={editor.initialValues} restored={editor.restored} draftContext={{ usuarioId: usuario.id, rota }}
          returnFocusRef={botaoEditar} onClose={fecharEditor} onSaved={(mensagem, atualizado) => {
            setPaciente(atualizado);
            setAviso(mensagem);
            fecharEditor();
          }} />
      )}
    </div>
  );
}
