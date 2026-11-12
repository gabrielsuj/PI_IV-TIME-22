import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import HorariosDisponiveis from '../components/agendamento/HorariosDisponiveis';

// Dados isolados do componente; não representam disponibilidade do ambiente real.
const horarios = [
  { inicio: '2026-12-01T12:00:00Z', fim: '2026-12-01T12:30:00Z' },
  { inicio: '2026-12-01T13:00:00Z', fim: '2026-12-01T13:30:00Z' },
];
const base = { status: 'sucesso', horarios, fusoHorario: 'America/Sao_Paulo', onChange: vi.fn() };

function Seletor() {
  const [value, setValue] = useState(null);
  return <HorariosDisponiveis {...base} value={value} onChange={setValue} />;
}

describe('seleção de horários', () => {
  it('exibe os instantes no fuso informado pela agenda e conserva os dados na seleção', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    const { rerender } = render(<HorariosDisponiveis {...base} onChange={onChange} />);
    expect(screen.getByText('Horários no fuso America/Sao_Paulo.')).toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: '09:00 às 09:30' }));
    expect(onChange).toHaveBeenCalledExactlyOnceWith(horarios[0]);
    rerender(<HorariosDisponiveis {...base} value={horarios[0]} onChange={onChange} />);
    expect(screen.getByRole('radio', { name: '09:00 às 09:30' })).toBeChecked();
    rerender(<HorariosDisponiveis {...base} fusoHorario="UTC" value={horarios[0]} onChange={onChange} />);
    expect(screen.getByRole('radio', { name: '12:00 às 12:30' })).toBeChecked();
  });

  it('permite escolher e trocar o horário pelo teclado', async () => {
    const user = userEvent.setup();
    render(<Seletor />);
    await user.tab();
    expect(screen.getByRole('radio', { name: '09:00 às 09:30' })).toHaveFocus();
    await user.keyboard(' ');
    expect(screen.getByRole('radio', { name: '09:00 às 09:30' })).toBeChecked();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('radio', { name: '10:00 às 10:30' })).toHaveFocus();
    expect(screen.getByRole('radio', { name: '10:00 às 10:30' })).toBeChecked();
    expect(screen.getByRole('radio', { name: '09:00 às 09:30' })).not.toBeChecked();
  });

  it.each([
    ['aguardando', 'Escolha a especialidade, o médico e a data'],
    ['carregando', 'Buscando horários disponíveis'],
    ['sem-agenda', 'O médico ainda não possui agenda configurada'],
  ])('distingue o estado %s sem oferecer opções antigas', (status, mensagem) => {
    render(<HorariosDisponiveis {...base} status={status} value={horarios[0]} />);
    expect(screen.getByRole('status')).toHaveTextContent(mensagem);
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    if (status === 'carregando') expect(screen.getByRole('region', { name: 'Horários disponíveis' })).toHaveAttribute('aria-busy', 'true');
  });

  it('exibe dia sem vagas somente após uma consulta bem-sucedida', () => {
    render(<HorariosDisponiveis {...base} horarios={[]} />);
    expect(screen.getByRole('status')).toHaveTextContent('Nenhum horário disponível nesta data.');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
  });

  it('oferece nova tentativa no erro e não o apresenta como lista vazia', async () => {
    const onRetry = vi.fn();
    const user = userEvent.setup();
    render(<HorariosDisponiveis {...base} status="erro" error={new Error('Falha de rede.')} onRetry={onRetry} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Não foi possível consultar os horários. Falha de rede.');
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(screen.queryByText(/Nenhum horário disponível/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('não mantém uma opção marcada quando ela sai da lista ou tem o intervalo alterado', () => {
    const { rerender } = render(<HorariosDisponiveis {...base} value={horarios[0]} />);
    expect(screen.getByRole('radio', { name: '09:00 às 09:30' })).toBeChecked();
    rerender(<HorariosDisponiveis {...base} horarios={[horarios[1]]} value={horarios[0]} />);
    expect(screen.getByRole('radio')).not.toBeChecked();
    rerender(<HorariosDisponiveis {...base} horarios={[{ ...horarios[0], fim: '2026-12-01T12:45:00Z' }]} value={horarios[0]} />);
    expect(screen.getByRole('radio', { name: '09:00 às 09:45' })).not.toBeChecked();
  });

  it('bloqueia a seleção enquanto a operação externa estiver ocupada', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<HorariosDisponiveis {...base} disabled onChange={onChange} />);
    const radio = screen.getByRole('radio', { name: '09:00 às 09:30' });
    expect(radio).toBeDisabled();
    await user.click(radio);
    expect(onChange).not.toHaveBeenCalled();
  });

  it('indica a data final quando o atendimento atravessa a meia-noite no fuso da agenda', () => {
    render(<HorariosDisponiveis {...base} horarios={[{ inicio: '2026-12-02T02:30:00Z', fim: '2026-12-02T03:00:00Z' }]} />);
    expect(screen.getByRole('radio', { name: '23:30 às 00:00 (02/12/2026)' })).toBeInTheDocument();
  });

  it.each([
    { fusoHorario: undefined },
    { fusoHorario: 'fuso-invalido' },
    { horarios: [{ inicio: '2026-12-01T09:00', fim: '2026-12-01T09:30' }] },
    { horarios: [{ inicio: 'invalido', fim: '2026-12-01T12:30:00Z' }] },
    { horarios: [{ ...horarios[0], fim: horarios[0].inicio }] },
    { horarios: [horarios[0], horarios[0]] },
  ])('não oferece seleção quando os dados de exibição são inválidos (%j)', (props) => {
    render(<HorariosDisponiveis {...base} {...props} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Não foi possível exibir os horários recebidos.');
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
  });
});
