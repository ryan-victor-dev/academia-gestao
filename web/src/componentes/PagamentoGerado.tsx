import { useState } from 'react';
import { dataHora, dinheiro } from '../formato';
import { QrCode } from './QrCode';

type Forma = 'PIX' | 'BOLETO' | 'CREDITO' | 'DEBITO';

export interface PagamentoOnline {
  id: string;
  cobrancaId: string;
  forma: Forma;
  total: number;
  pixCopiaECola: string | null;
  boletoLinhaDigitavel: string | null;
  boletoUrl: string | null;
  linkDePagamento: string | null;
  validoAte: string;
  situacao: string;
}

/** O prazo vem como o início do dia seguinte: mostra o último minuto do dia em que vale (23:59). */
export const ultimoMinuto = (validoAte: string) => dataHora(new Date(Date.parse(validoAte) - 60_000).toISOString());

export function Gerado({ titulo, codigo, pagamento, aviso, qr = false }: { titulo: string; codigo: string; pagamento: PagamentoOnline; aviso: string; qr?: boolean }) {
  const [copiado, setCopiado] = useState(false);

  async function copiar() {
    try {
      await navigator.clipboard.writeText(codigo);
      setCopiado(true);
    } catch {
      setCopiado(false);
    }
  }

  return (
    <div className="credenciais">
      <p><strong>{titulo}</strong> · {dinheiro(pagamento.total)}</p>
      {qr && codigo && <QrCode texto={codigo} rotulo="QR code do Pix" />}
      <textarea readOnly aria-label={titulo} value={codigo} rows={3} onFocus={(e) => e.currentTarget.select()} />
      <div className="acoes">
        <button type="button" onClick={copiar}>Copiar</button>
        {pagamento.boletoUrl && <a href={pagamento.boletoUrl} target="_blank" rel="noopener noreferrer">Abrir o boleto</a>}
        {copiado && <span role="status">Copiado.</span>}
      </div>
      <p className="suave">{aviso}</p>
    </div>
  );
}
