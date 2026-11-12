import { useState } from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import ResumoAgendamento from '../components/agendamento/ResumoAgendamento';
import HorariosDisponiveis from '../components/agendamento/HorariosDisponiveis';

// Dados isolados de teste; a confirmação de produção será fornecida pela API.
const horario = { inicio: '2026-12-01T12:00:00Z', fim: '2026-12-01T12:30:00Z' };
const outroHorario = { inicio: '2026-12-01T13:00:00Z', fim: '2026-12-01T13:30:00Z' };
const base = {
  usuario: { id: 'u1', nome: 'Secretaria Teste', perfil: 'SECRETARIA_ADMIN' },
  paciente: { id: 'p1', nome: 'Maria Teste', cpf: '01234567890' },
  especialidade: { id: 'e1', nome: 'Cardiologia', ativa: true },
  medico: { id: 'm1', nome: 'Ana Teste', crmNumero: '000123', crmUf: 'SP', especialidadeIds: ['e1'], ativo: true },
  horario, fusoHorario: 'America/Sao_Paulo',
};

describe('resumo do agendamento', () => {
  it('apresenta paciente, médico, especialidade e o mesmo período exibido na seleção', () => {
    render(<ResumoAgendamento {...base} />);
    expect(screen.getByRole('region', { name: 'Revise sua consulta' })).toBeInTheDocument();
    for (const texto of ['Maria Teste', '01234567890', 'Cardiologia', 'Ana Teste', '000123/SP', '01/12/2026', '09:00 às 09:30', 'America/Sao_Paulo']) {
      expect(screen.getByText(texto)).toBeInTheDocument();
    }
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeDisabled();
  });

  it('identifica o próprio paciente sem usar o ID do usuário como ID de cadastro', async () => {
    const onConfirmar = vi.fn();
    render(<ResumoAgendamento {...base} usuario={{ id: 'usuario-diferente-do-paciente', nome: 'Paulo Teste', perfil: 'PACIENTE' }} paciente={null} onConfirmar={onConfirmar} />);
    expect(screen.getByText('Paulo Teste (você)')).toBeInTheDocument();
    expect(screen.queryByText('CPF')).not.toBeInTheDocument();
    expect(screen.queryByText('usuario-diferente-do-paciente')).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Confirmar agendamento' }));
    expect(onConfirmar).toHaveBeenCalledExactlyOnceWith();
  });

  it('não confirma automaticamente e permite enviar pelo teclado', async () => {
    const onConfirmar = vi.fn();
    const user = userEvent.setup();
    render(<ResumoAgendamento {...base} onConfirmar={onConfirmar} onVoltar={vi.fn()} />);
    expect(onConfirmar).not.toHaveBeenCalled();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Voltar para os horários' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(onConfirmar).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/agendamento confirmado/i)).not.toBeInTheDocument();
  });

  it('bloqueia voltar e reenviar durante a confirmação', async () => {
    const onConfirmar = vi.fn();
    const onVoltar = vi.fn();
    const user = userEvent.setup();
    render(<ResumoAgendamento {...base} confirmando onConfirmar={onConfirmar} onVoltar={onVoltar} />);
    const button = screen.getByRole('button', { name: 'Confirmando…' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByRole('button', { name: 'Voltar para os horários' })).toBeDisabled();
    fireEvent.submit(button.closest('form'));
    await user.click(button);
    expect(onConfirmar).not.toHaveBeenCalled();
    expect(onVoltar).not.toHaveBeenCalled();
  });

  it.each([0, 400, 403, 500])('mantém o resumo e permite tentativa manual após erro %s', (status) => {
    const { rerender } = render(<ResumoAgendamento {...base} confirmando onConfirmar={vi.fn()} onVoltar={vi.fn()} />);
    rerender(<ResumoAgendamento {...base} error={{ status, message: 'Não foi possível concluir a solicitação.' }} onConfirmar={vi.fn()} onVoltar={vi.fn()} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Não foi possível concluir a solicitação.');
    expect(screen.getByText('Maria Teste')).toBeInTheDocument();
    expect(screen.getByText('09:00 às 09:30')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Voltar para os horários' })).toBeEnabled();
  });

  it('exige outra escolha após 409 sem perder os outros dados', async () => {
    const onConfirmar = vi.fn();
    const onVoltar = vi.fn();
    const user = userEvent.setup();
    const { rerender } = render(<ResumoAgendamento {...base} error={{ status: 409 }} onConfirmar={onConfirmar} onVoltar={onVoltar} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Este horário não está mais disponível.');
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeDisabled();
    for (const texto of ['Maria Teste', 'Cardiologia', 'Ana Teste', '01/12/2026']) expect(screen.getByText(texto)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Escolher outro horário' }));
    expect(onVoltar).toHaveBeenCalledTimes(1);
    expect(onConfirmar).not.toHaveBeenCalled();
    rerender(<ResumoAgendamento {...base} horario={outroHorario} onConfirmar={onConfirmar} onVoltar={onVoltar} />);
    expect(screen.getByText('10:00 às 10:30')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeEnabled();
  });

  it('retorna à seleção com o mesmo horário marcado', async () => {
    function Fluxo() {
      const [revisando, setRevisando] = useState(true);
      const [value, setValue] = useState(horario);
      return revisando
        ? <ResumoAgendamento {...base} horario={value} onVoltar={() => setRevisando(false)} />
        : <HorariosDisponiveis status="sucesso" horarios={[horario, outroHorario]} fusoHorario={base.fusoHorario} value={value} onChange={setValue} />;
    }
    render(<Fluxo />);
    await userEvent.setup().click(screen.getByRole('button', { name: 'Voltar para os horários' }));
    expect(screen.getByRole('radio', { name: '09:00 às 09:30' })).toBeChecked();
  });

  it('formata a data no fuso da agenda mesmo quando ela difere da data UTC', () => {
    render(<ResumoAgendamento {...base} horario={{ inicio: '2026-12-02T02:30:00Z', fim: '2026-12-02T03:00:00Z' }} />);
    expect(screen.getByText('01/12/2026')).toBeInTheDocument();
    expect(screen.getByText('23:30 às 00:00 (02/12/2026)')).toBeInTheDocument();
  });

  it.each([
    { paciente: null },
    { especialidade: { ...base.especialidade, ativa: false } },
    { medico: { ...base.medico, ativo: false } },
    { medico: { ...base.medico, especialidadeIds: ['outra-especialidade'] } },
    { horario: null },
    { fusoHorario: undefined },
  ])('impede confirmação com dados incompletos ou incompatíveis (%j)', (props) => {
    render(<ResumoAgendamento {...base} {...props} onConfirmar={vi.fn()} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Revise os dados');
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeDisabled();
  });

  it('não oferece confirmação para o perfil médico nem exibe os dados do paciente', () => {
    render(<ResumoAgendamento {...base} usuario={{ id: 'medico', perfil: 'MEDICO' }} onConfirmar={vi.fn()} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Seu perfil não permite agendar consultas.');
    expect(screen.getByRole('button', { name: 'Confirmar agendamento' })).toBeDisabled();
    expect(within(screen.getByRole('region', { name: 'Revise sua consulta' })).queryByText('Maria Teste')).not.toBeInTheDocument();
  });
});
