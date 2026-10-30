import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { dinheiro, hora } from '../../formato';
import { chamar } from '../../sessao/api';
import { NOME_DO_MEIO, type Meio } from '../cobrancas/tipos';

export interface LancamentoDoDia {
  id: string;
  tipo: 'PAGAMENTO' | 'ESTORNO';
  em: string;
  forma: Meio;
  total: number;
  codigoTransacao: string | null;
  alunoId: string;
  alunoNome: string | null;
  porNome: string | null;
  motivo: string | null;
}

/**
 * Só o ADMIN (ADR 0046, item 10): cada pagamento e estorno do caixa do dia, para conferir o Pix
 * com o extrato e o cartão com a maquininha, pelo código da transação. `versao` muda
 * quando o caixa é recarregado (fechado, reaberto): a lista acompanha.
 */
export function LancamentosDoDia({ dia, versao }: { dia: string; versao: unknown }) {
  const [lista, setLista] = useState<LancamentoDoDia[] | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => {
    let vale = true;
    setErro(null);
    chamar<LancamentoDoDia[]>(`/api/caixas/${dia}/lancamentos`)
      .then((l) => { if (vale) setLista(l); })
      .catch((e: unknown) => { if (vale) setErro(e); });
    return () => { vale = false; };
  }, [dia, versao]);

  return (
    <section>
      <h2>Pagamentos e estornos do dia</h2>
      <MensagemDeErro erro={erro} />
      {lista?.length === 0 && <p className="suave">Nenhum pagamento nem estorno neste dia.</p>}
      {!!lista?.length && (
        <table className="tabela">
          <thead><tr><th>Hora</th><th>Valor</th><th>Código</th><th>Aluno</th><th>Quem</th></tr></thead>
          <tbody>
            {lista.map((l) => (
              <tr key={l.id}>
                <td>{hora(l.em)}</td>
                <td>{l.tipo === 'ESTORNO' ? <>Estorno de {dinheiro(l.total)}</> : dinheiro(l.total)}
                  <br /><span className="suave">{NOME_DO_MEIO[l.forma]}</span>
                  {l.motivo && <><br /><span className="suave">{l.motivo}</span></>}</td>
                <td>{l.codigoTransacao ? <code>{l.codigoTransacao}</code> : <span className="suave">—</span>}</td>
                <td><Link to={`/alunos/${l.alunoId}`}>{l.alunoNome ?? 'abrir o aluno'}</Link></td>
                <td>{l.porNome ?? <span className="suave">—</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
