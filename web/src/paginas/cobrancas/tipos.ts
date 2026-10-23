export type Meio = 'DINHEIRO' | 'PIX' | 'CARTAO_DEBITO' | 'CARTAO_CREDITO';
export type SituacaoDaCobranca = 'PENDENTE' | 'ATRASADA' | 'PAGA' | 'CANCELADA';

export interface Devido { valor: number; acrescimo: number; juros: number; total: number }

export interface Cobranca {
  id: string;
  alunoId: string;
  tipo: 'MENSALIDADE' | 'A_VISTA' | 'MULTA';
  parcela: number;
  valor: number;
  vencimento: string;
  prazo: string;
  situacao: SituacaoDaCobranca;
  diasDeAtraso: number;
  devidoHoje: Devido | null;
  pagamento: { total: number; acrescimo: number; juros: number; meio: Meio; em: string } | null;
  cancelamento: { em: string; motivo: string } | null;
}

export interface CobrancaAtrasada extends Cobranca {
  alunoNome: string | null;
}

export interface Lancamento {
  id: string;
  tipo: 'PAGAMENTO' | 'ESTORNO';
  total: number;
  meio: Meio;
  em: string;
  motivo: string | null;
}

export const NOME_DO_MEIO: Record<Meio, string> = {
  DINHEIRO: 'Dinheiro', PIX: 'Pix', CARTAO_DEBITO: 'Cartão de débito', CARTAO_CREDITO: 'Cartão de crédito',
};

export const NOME_DA_SITUACAO: Record<SituacaoDaCobranca, string> = {
  PENDENTE: 'Pendente', ATRASADA: 'Atrasada', PAGA: 'Paga', CANCELADA: 'Cancelada',
};

export function descricao(c: Pick<Cobranca, 'tipo' | 'parcela'>): string {
  if (c.tipo === 'MENSALIDADE') return `Mensalidade ${c.parcela}`;
  if (c.tipo === 'A_VISTA') return 'Pagamento à vista';
  return 'Multa de cancelamento';
}
