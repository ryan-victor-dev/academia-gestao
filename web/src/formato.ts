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

/** Instante → "14:05", no fuso da academia (para listas de um dia só). */
export function hora(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('pt-BR', { timeZone: FUSO, timeStyle: 'short' }).format(new Date(iso));
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

/**
 * Anos completos de quem nasceu na data ("1990-05-10"), hoje no fuso da academia (ADR 0048). Quem nasceu em 29/02
 * completa em 01/03 nos anos comuns, como no servidor. Sem data, nulo.
 */
export function idade(nascimento: string | null | undefined, dia: string = hoje()): number | null {
  if (!nascimento) return null;
  const [a, m, d] = nascimento.split('-').map(Number);
  const [ha, hm, hd] = dia.split('-').map(Number);
  return ha - a - (hm < m || (hm === m && hd < d) ? 1 : 0);
}

/** "35 anos", "1 ano"; sem data, "—". */
export function anos(nascimento: string | null | undefined): string {
  const n = idade(nascimento);
  return n === null ? '—' : `${n} ${n === 1 ? 'ano' : 'anos'}`;
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

/** As partes de um instante no fuso da academia: "2026-11-20T19:00". */
function localNaAcademia(instante: number): string {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: FUSO, hourCycle: 'h23', year: 'numeric',
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).formatToParts(new Date(instante))
    .map((parte) => [parte.type, parte.value]));
  return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`;
}

/**
 * Valor de um `<input type="datetime-local">` ("2026-11-20T19:00"), lido como hora da academia, → instante ISO.
 * O campo não tem fuso: sem isto, a hora seguiria o fuso do aparelho de quem digitou.
 */
export function instanteDaAcademia(local: string): string {
  const comoSeFosseUtc = Date.parse(`${local}:00Z`);
  const deslocamento = Date.parse(`${localNaAcademia(comoSeFosseUtc)}:00Z`) - comoSeFosseUtc;
  return new Date(comoSeFosseUtc - deslocamento).toISOString();
}

/** Instante ISO → valor de `<input type="datetime-local">` na hora da academia (o contrário do de cima). */
export function paraCampoDeDataHora(iso: string | null | undefined): string {
  return iso ? localNaAcademia(Date.parse(iso)) : '';
}
