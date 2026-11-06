import { useState } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../services/api';
import Alert from '../components/ui/Alert';
import Button from '../components/ui/Button';
import Card from '../components/ui/Card';
import Field from '../components/ui/Field';
import PageHeader from '../components/ui/PageHeader';

export default function LoginPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState({});
  const [failure, setFailure] = useState('');
  const [loading, setLoading] = useState(false);

  async function submit(event) {
    event.preventDefault();
    const nextErrors = {};
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) nextErrors.email = 'Informe um e-mail válido.';
    if (!password) nextErrors.password = 'Informe sua senha.';
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length) {
      event.currentTarget.querySelector(`[name="${Object.keys(nextErrors)[0]}"]`)?.focus();
      return;
    }
    setLoading(true);
    setFailure('');
    try {
      const data = await api.post('/auth/login', { email: email.trim(), senha: password });
      if (typeof data?.token !== 'string' || !data.token.trim()) throw new Error('Não foi possível concluir o acesso. Tente novamente.');
      sessionStorage.setItem('consultafacil.accessToken', data.token);
      navigate('/');
    } catch (error) {
      setFailure(error.message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="login-page">
      <Card title="Acesse sua conta" description="Entre para organizar seus atendimentos.">
        <form className="login-form" onSubmit={submit} noValidate>
          <Field name="email" label="E-mail" type="email" autoComplete="username" placeholder="seu@email.com" required value={email} onChange={(event) => { setEmail(event.target.value); setErrors((current) => ({ ...current, email: undefined })); }} error={errors.email} disabled={loading} />
          <Field name="password" label="Senha" type="password" autoComplete="current-password" required value={password} onChange={(event) => { setPassword(event.target.value); setErrors((current) => ({ ...current, password: undefined })); }} error={errors.password} disabled={loading} />
          {failure && <Alert>{failure}</Alert>}
          <Button type="submit" loading={loading}>{loading ? 'Entrando…' : 'Entrar'}</Button>
        </form>
      </Card>
      <PageHeader title="ConsultaFácil" description="Agendamento de consultas médicas em um só lugar." />
    </div>
  );
}
