import { describe, expect, it } from 'vitest';
import { janelaBloqueios } from '../utils/horario';

describe('janela de bloqueios', () => {
  it('ajusta o aniversário de 29 de fevereiro para o último dia válido', () => {
    expect(janelaBloqueios('America/Sao_Paulo', new Date('2028-02-29T15:00:00Z')))
      .toEqual({ de: '2028-02-29', ate: '2029-02-28' });
  });

  it('usa a data da agenda mesmo quando UTC já está no dia seguinte', () => {
    const agora = new Date('2028-03-01T01:00:00Z');
    expect(janelaBloqueios('America/Sao_Paulo', agora)).toEqual({ de: '2028-02-29', ate: '2029-02-28' });
    expect(janelaBloqueios('Asia/Tokyo', agora)).toEqual({ de: '2028-03-01', ate: '2029-03-01' });
    expect(janelaBloqueios(undefined, agora)).toEqual(janelaBloqueios('America/Sao_Paulo', agora));
  });
});
