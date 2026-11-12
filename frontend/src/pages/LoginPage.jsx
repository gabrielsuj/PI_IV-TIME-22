import { useEffect, useRef, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router';
import Alert from '../components/ui/Alert';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import Field from '../components/ui/Field';
import PageHeader from '../components/ui/PageHeader';
import { useAuth } from '../hooks/useAuth';

/** Para onde ir depois do login: a página que pediu o login, ou o início. */
function destinoDepoisDoLogin(location) {
  const from = location.state?.from;
  if (!from?.pathname || from.pathname === '/login') return '/';
  return `${from.pathname}${from.search ?? ''}`;
}

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { usuario, sessaoExpirada, entrar } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState({});
  const [failure, setFailure] = useState('');
  const [loading, setLoading] = useState(false);
  const sending = useRef(false);
  const failureRef = useRef(null);
  const destino = destinoDepoisDoLogin(location);

  useEffect(() => {
    if (failure) failureRef.current?.focus();
  }, [failure]);

  if (usuario && !loading) return <Navigate to={destino} replace />;

  async function submit(event) {
    event.preventDefault();
    if (sending.current) return;
    const nextErrors = {};
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) nextErrors.email = 'Informe um e-mail válido.';
    if (!password) nextErrors.password = 'Informe sua senha.';
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) {
      event.currentTarget.querySelector(`[name="${Object.keys(nextErrors)[0]}"]`)?.focus();
      return;
    }
    sending.current = true;
    setLoading(true);
    setFailure('');
    try {
      await entrar(email.trim(), password);
      navigate(destino, { replace: true });
    } catch (error) {
      setFailure(error.message);
      setLoading(false);
    } finally {
      sending.current = false;
    }
  }

  return (
    <div className="login-screen">
      <div className="login-page">
        <Card title="Acesse sua conta" description="Entre para organizar seus atendimentos.">
          <form className="login-form" onSubmit={submit} noValidate>
            {sessaoExpirada && !failure && <Alert tone="info">Sua sessão expirou. Entre novamente para continuar.</Alert>}
            <Field name="email" label="E-mail" type="email" autoComplete="username" placeholder="seu@email.com" required value={email} onChange={(event) => { setEmail(event.target.value); setErrors((current) => ({ ...current, email: undefined })); }} error={errors.email} disabled={loading} />
            <Field name="password" label="Senha" type="password" autoComplete="current-password" required value={password} onChange={(event) => { setPassword(event.target.value); setErrors((current) => ({ ...current, password: undefined })); }} error={errors.password} disabled={loading} />
            {failure && <Alert ref={failureRef} tabIndex={-1}>{failure}</Alert>}
            <Button type="submit" loading={loading}>{loading ? 'Entrando…' : 'Entrar'}</Button>
          </form>
        </Card>
        <PageHeader title="ConsultaFácil" description="Agendamento de consultas médicas em um só lugar." />
      </div>
    </div>
  );
}
