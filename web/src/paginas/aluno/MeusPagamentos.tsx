import { useEffect, useState } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { data, dataHora, dinheiro } from '../../formato';
import { chamar } from '../../sessao/api';
import { descricao, NOME_DA_SITUACAO, NOME_DO_MEIO, type Devido, type Meio, type SituacaoDaCobranca } from '../cobrancas/tipos';

export interface CobrancaDoAluno {
  id: string;
  tipo: 'MENSALIDADE' | 'A_VISTA' | 'MULTA';
  parcela: number;
  valor: number;
  vencimento: string;
  situacao: SituacaoDaCobranca;
  diasDeAtraso: number;
  devidoHoje: Devido | null;
  pagaEm: string | null;
  meioDePagamento: Meio | null;
  pagoTotal: number | null;
}

/** As cobranças do aluno: primeiro o que está em aberto (com o valor de hoje), depois o pago. */
export function MeusPagamentos() {
  const [cobrancas, setCobrancas] = useState<CobrancaDoAluno[] | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => { chamar<CobrancaDoAluno[]>('/api/cobrancas/me').then(setCobrancas).catch(setErro); }, []);

  if (!cobrancas) return erro ? <MensagemDeErro erro={erro} /> : <p className="suave" role="status">Carregando…</p>;
  const abertas = cobrancas.filter((c) => c.situacao === 'PENDENTE' || c.situacao === 'ATRASADA');
  const outras = cobrancas.filter((c) => c.situacao === 'PAGA' || c.situacao === 'CANCELADA');

  return (
    <>
      <h1>Pagamentos</h1>
      <p className="suave">O pagamento é feito na recepção.</p>
      <h2>Em aberto</h2>
      {abertas.length === 0 && <p>Nada em aberto.</p>}
      <ul className="cartoes">
        {abertas.map((c) => (
          <li key={c.id} className="cartao-item">
            <div className="cabecalho">
              <strong>{descricao(c)}</strong>
              <span className={`etiqueta cobranca-${c.situacao.toLowerCase()}`}>{NOME_DA_SITUACAO[c.situacao]}</span>
            </div>
            <p>Vence em {data(c.vencimento)}{c.situacao === 'ATRASADA' && ` · ${c.diasDeAtraso} ${c.diasDeAtraso === 1 ? 'dia' : 'dias'} de atraso`}</p>
            <p className="valor">{dinheiro(c.devidoHoje?.total ?? c.valor)}</p>
            {c.devidoHoje && (c.devidoHoje.acrescimo > 0 || c.devidoHoje.juros > 0) && (
              <p className="suave">{dinheiro(c.valor)} + acréscimo {dinheiro(c.devidoHoje.acrescimo)} + juros {dinheiro(c.devidoHoje.juros)}</p>
            )}
          </li>
        ))}
      </ul>
      {outras.length > 0 && (
        <>
          <h2>Histórico</h2>
          <ul className="lista">
            {outras.map((c) => (
              <li key={c.id} className="linha">
                <span className="principal">{descricao(c)}</span>
                {c.situacao === 'PAGA'
                  ? <span>Paga em {dataHora(c.pagaEm)} · {dinheiro(c.pagoTotal)}{c.meioDePagamento && ` (${NOME_DO_MEIO[c.meioDePagamento]})`}</span>
                  : <span className="suave">Cancelada</span>}
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}
