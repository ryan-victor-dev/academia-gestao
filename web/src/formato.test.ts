import { describe, expect, it } from 'vitest';
import { cpf, data, dataHora, diaDe, diaSeguinte, dinheiro, instanteDaAcademia, paraCampoDeDataHora, telefone } from './formato';

describe('formatos', () => {
  it('data pura não muda de dia por causa do fuso', () => {
    expect(data('2026-01-01')).toBe('01/01/2026');
    expect(data(null)).toBe('—');
  });

  it('instante aparece no fuso da academia', () => {
    // 02:30 em UTC é 23:30 do dia anterior em São Paulo.
    expect(dataHora('2026-10-24T02:30:00Z')).toBe('23/10/2026, 23:30');
  });

  it('dinheiro em reais', () => {
    expect(dinheiro(99.9).replace(/\s/g, ' ')).toBe('R$ 99,90');
    expect(dinheiro('1234.5').replace(/\s/g, ' ')).toBe('R$ 1.234,50');
  });

  it('CPF com pontuação', () => {
    expect(cpf('12345678909')).toBe('123.456.789-09');
  });

  it('telefone com DDD, celular e fixo', () => {
    expect(telefone('11999990000')).toBe('(11) 99999-0000');
    expect(telefone('1133334444')).toBe('(11) 3333-4444');
    expect(telefone(null)).toBe('—');
  });

  it('dia seguinte vira o mês e o ano', () => {
    expect(diaSeguinte('2026-10-31')).toBe('2026-11-01');
    expect(diaSeguinte('2026-12-31')).toBe('2027-01-01');
  });

  it('dia de um instante no fuso da academia', () => {
    expect(diaDe('2026-10-24T02:30:00Z')).toBe('2026-10-23');
  });

  it('campo de data e hora: lido e mostrado na hora da academia, qualquer que seja o fuso do aparelho', () => {
    expect(instanteDaAcademia('2026-11-20T19:00')).toBe('2026-11-20T22:00:00.000Z');
    expect(instanteDaAcademia('2026-12-31T23:30')).toBe('2027-01-01T02:30:00.000Z');
    expect(paraCampoDeDataHora('2026-11-20T22:00:00Z')).toBe('2026-11-20T19:00');
    expect(paraCampoDeDataHora('2027-01-01T02:30:00Z')).toBe('2026-12-31T23:30');
    expect(paraCampoDeDataHora(null)).toBe('');
  });
});
