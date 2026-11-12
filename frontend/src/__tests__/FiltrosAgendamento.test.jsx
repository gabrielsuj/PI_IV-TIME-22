import { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import FiltrosAgendamento from '../components/agendamento/FiltrosAgendamento';

const especialidades = [
  { id: 'e1', nome: 'Cardiologia', ativa: true },
  { id: 'e2', nome: 'Clínica Geral', ativa: true },
  { id: 'e3', nome: 'Dermatologia', ativa: true },
];
const medicos = [
  { id: 'm1', nome: 'Ana Teste', crmNumero: '000123', crmUf: 'SP', especialidadeIds: ['e1'], ativo: true },
  { id: 'm2', nome: 'Bruno Teste', crmNumero: '004567', crmUf: 'SP', especialidadeIds: ['e2'], ativo: true },
];

function Formulario({ initialValue = { especialidadeId: '', medicoId: '', data: '' }, ...props }) {
  const [value, setValue] = useState(initialValue);
  return <FiltrosAgendamento especialidades={especialidades} medicos={medicos} value={value} onChange={setValue} {...props} />;
}

describe('filtros do agendamento', () => {
  it('libera os campos em sequência e mostra apenas médicos da especialidade', async () => {
    const user = userEvent.setup();
    render(<Formulario />);
    expect(screen.getByLabelText(/Médico/)).toBeDisabled();
    expect(screen.getByLabelText(/Data da consulta/)).toBeDisabled();
    await user.selectOptions(screen.getByLabelText(/Especialidade/), 'e1');
    expect(screen.getByRole('option', { name: /Ana Teste — CRM 000123\/SP/ })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /Bruno Teste/ })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText(/Médico/), 'm1');
    expect(screen.getByLabelText(/Data da consulta/)).toBeEnabled();
    fireEvent.change(screen.getByLabelText(/Data da consulta/), { target: { value: '2026-12-01' } });
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
  });

  it('limpa o médico ao trocar a especialidade e preserva a data preenchida', async () => {
    const user = userEvent.setup();
    render(<Formulario initialValue={{ especialidadeId: 'e1', medicoId: 'm1', data: '2026-12-01' }} />);
    await user.selectOptions(screen.getByLabelText(/Especialidade/), 'e2');
    expect(screen.getByLabelText(/Médico/)).toHaveValue('');
    expect(screen.queryByRole('option', { name: /Ana Teste/ })).not.toBeInTheDocument();
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    expect(screen.getByLabelText(/Data da consulta/)).toBeDisabled();
    await user.selectOptions(screen.getByLabelText(/Médico/), 'm2');
    expect(screen.getByLabelText(/Data da consulta/)).toBeEnabled();
  });

  it('distingue catálogo vazio de especialidade sem médicos', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<Formulario especialidades={[]} medicos={[]} />);
    expect(screen.getByRole('status')).toHaveTextContent('Nenhuma especialidade ativa encontrada.');
    expect(screen.getByLabelText(/Especialidade/)).toBeDisabled();
    rerender(<Formulario />);
    await user.selectOptions(screen.getByLabelText(/Especialidade/), 'e3');
    expect(screen.getByRole('status')).toHaveTextContent('Nenhum médico ativo nesta especialidade.');
    expect(screen.getByLabelText(/Médico/)).toBeDisabled();
  });

  it('preserva os filtros durante falha e nova tentativa, sem mostrar erro como vazio', async () => {
    const retry = vi.fn();
    const initialValue = { especialidadeId: 'e1', medicoId: 'm1', data: '2026-12-01' };
    const { rerender } = render(<Formulario initialValue={initialValue} error={new Error('Falha de rede.')} onRetry={retry} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Falha de rede.');
    expect(screen.queryByText(/Nenhuma especialidade/)).not.toBeInTheDocument();
    expect(screen.getByLabelText(/Médico/)).toBeDisabled();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(retry).toHaveBeenCalledTimes(1);
    rerender(<Formulario initialValue={initialValue} loading />);
    expect(screen.getByRole('status')).toHaveTextContent('Carregando');
    expect(screen.getByLabelText(/Especialidade/)).toHaveValue('e1');
    expect(screen.getByLabelText(/Médico/)).toHaveValue('m1');
    rerender(<Formulario initialValue={initialValue} />);
    expect(screen.getByLabelText(/Data da consulta/)).toHaveValue('2026-12-01');
    expect(screen.getByLabelText(/Médico/)).toBeEnabled();
  });

  it('não aceita na interface um médico que não pertence à especialidade', () => {
    render(<Formulario initialValue={{ especialidadeId: 'e1', medicoId: 'm2', data: '' }} />);
    expect(screen.getByLabelText(/Médico/)).toHaveValue('');
    expect(screen.getByLabelText(/Data da consulta/)).toBeDisabled();
  });
});
