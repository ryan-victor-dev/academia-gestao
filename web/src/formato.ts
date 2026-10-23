// Formatos de exibição (pt-BR, fuso da academia).

const FUSO = 'America/Sao_Paulo';

/** "2026-10-23" → "23/10/2026". Sem passar por Date: data pura não tem fuso. */
export function data(iso: string | null | undefined): string {
  if (!iso) return '—';
  const [ano, mes, dia] = iso.split('-');
  return `${dia}/${mes}/${ano}`;
}

/** Instante (ISO com fuso) → "23/10/2026 14:05", no fuso da academia. */
export function dataHora(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('pt-BR', { timeZone: FUSO, dateStyle: 'short', timeStyle: 'short' })
    .format(new Date(iso));
}

const REAIS = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

export function dinheiro(valor: number | string | null | undefined): string {
  if (valor === null || valor === undefined) return '—';
  return REAIS.format(Number(valor));
}

/** "12345678909" → "123.456.789-09". */
export function cpf(valor: string | null | undefined): string {
  if (!valor) return '—';
  const d = valor.replace(/\D/g, '');
  return d.length === 11 ? `${d.slice(0, 3)}.${d.slice(3, 6)}.${d.slice(6, 9)}-${d.slice(9)}` : valor;
}

/** "11999990000" → "(11) 99999-0000"; "1133334444" → "(11) 3333-4444" (a API guarda só os dígitos). */
export function telefone(valor: string | null | undefined): string {
  if (!valor) return '—';
  const d = valor.replace(/\D/g, '');
  if (d.length === 11) return `(${d.slice(0, 2)}) ${d.slice(2, 7)}-${d.slice(7)}`;
  if (d.length === 10) return `(${d.slice(0, 2)}) ${d.slice(2, 6)}-${d.slice(6)}`;
  return valor;
}

/** Hoje no fuso da academia, no formato dos campos de data ("2026-10-23"). */
export function hoje(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: FUSO }).format(new Date());
}

/** O dia seguinte a uma data ("2026-10-31" → "2026-11-01"), sem passar por fuso. */
export function diaSeguinte(iso: string): string {
  const [a, m, d] = iso.split('-').map(Number);
  const seguinte = new Date(Date.UTC(a, m - 1, d + 1));
  return seguinte.toISOString().slice(0, 10);
}

/** O dia (no fuso da academia) de um instante: "2026-10-24T02:30:00Z" → "2026-10-23". */
export function diaDe(instante: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: FUSO }).format(new Date(instante));
}
