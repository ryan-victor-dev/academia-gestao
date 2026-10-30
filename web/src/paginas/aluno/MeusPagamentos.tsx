import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { Gerado, ultimoMinuto, type PagamentoOnline } from '../../componentes/PagamentoGerado';
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

type Forma = 'PIX' | 'BOLETO' | 'CREDITO' | 'DEBITO';


interface Pagavel {
  cobrancaId: string; total: number;
  pix: PagamentoOnline | null; boleto: PagamentoOnline | null; credito: PagamentoOnline | null; debito: PagamentoOnline | null;
}

export interface OpcoesOnline {
  ligado: boolean; simulado: boolean; pix: boolean; boleto: boolean; credito: boolean; debito: boolean; pagaveis: Pagavel[];
}

/** A página da empresa de pagamentos fica fora do app: o navegador sai e volta (separado para os testes). */
export const paginaExterna = { abrir: (endereco: string) => window.location.assign(endereco) };

/**
 * As cobranças do aluno: primeiro o que está em aberto (com o valor de hoje), depois o pago. Com
 * o pagamento online ligado (ADRs 0034 e 0037), as que ele pode pagar ganham o Pix, o boleto e o
 * cartão (pago na página da empresa: nenhum dado de cartão passa pelo app).
 */
export function MeusPagamentos() {
  const [cobrancas, setCobrancas] = useState<CobrancaDoAluno[] | null>(null);
  const [online, setOnline] = useState<OpcoesOnline | null>(null);
  const [erro, setErro] = useState<unknown>(null);
  const [erroOnline, setErroOnline] = useState<unknown>(null);

  const carregar = useCallback(() => {
    setErroOnline(null);
    chamar<CobrancaDoAluno[]>('/api/cobrancas/me').then(setCobrancas).catch(setErro);
    chamar<OpcoesOnline>('/api/pagamentos-online/me').then(setOnline).catch(setErroOnline);
  }, []);

  useEffect(carregar, [carregar]);

  if (!cobrancas) return erro ? <MensagemDeErro erro={erro} /> : <p className="suave" role="status">Carregando…</p>;
  const abertas = cobrancas.filter((c) => c.situacao === 'PENDENTE' || c.situacao === 'ATRASADA');
  const outras = cobrancas.filter((c) => c.situacao === 'PAGA' || c.situacao === 'CANCELADA');
  const ligado = online?.ligado === true && (online.pix || online.boleto || online.credito || online.debito);

  return (
    <>
      <h1>Pagamentos</h1>
      <p className="suave">{ligado ? 'Pague pelo app, com Pix, boleto ou cartão, ou na recepção.' : 'O pagamento é feito na recepção.'}</p>
      {online?.simulado && (
        <p className="aviso" role="note">Modo de teste: os pagamentos pelo app são simulados e não cobram nada.</p>
      )}
      <MensagemDeErro erro={erroOnline} />
      <h2>Em aberto</h2>
      {abertas.length === 0 && <p>Nada em aberto.</p>}
      <ul className="cartoes">
        {abertas.map((c) => {
          const pagavel = ligado ? online.pagaveis.find((p) => p.cobrancaId === c.id) : undefined;
          return (
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
              {pagavel && <PagarPeloApp pagavel={pagavel} opcoes={online!} aoMudar={carregar} />}
            </li>
          );
        })}
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

/** Os botões de Pix e boleto de uma cobrança, e o que já foi gerado para ela. */
function PagarPeloApp({ pagavel, opcoes, aoMudar }: { pagavel: Pagavel; opcoes: OpcoesOnline; aoMudar: () => void }) {
  const [gerados, setGerados] = useState<Partial<Record<Forma, PagamentoOnline>>>(
    { ...(pagavel.pix ? { PIX: pagavel.pix } : {}), ...(pagavel.boleto ? { BOLETO: pagavel.boleto } : {}) });
  const [erro, setErro] = useState<unknown>(null);
  const [ocupado, setOcupado] = useState(false);
  const navegar = useNavigate();

  async function gerar(forma: Forma) {
    setErro(null);
    setOcupado(true);
    try {
      const novo = await chamar<PagamentoOnline>('/api/pagamentos-online/me', { metodo: 'POST', corpo: { cobrancaId: pagavel.cobrancaId, forma } });
      setGerados((g) => ({ ...g, [forma]: novo }));
    } catch (e) {
      setErro(e);
    } finally {
      setOcupado(false);
    }
  }

  /** O cartão: vai à página da empresa (a que ainda vale, ou uma nova). */
  async function pagarComCartao(forma: 'CREDITO' | 'DEBITO') {
    const valendo = forma === 'CREDITO' ? pagavel.credito : pagavel.debito;
    setErro(null);
    setOcupado(true);
    try {
      const pagamento = valendo ?? await chamar<PagamentoOnline>('/api/pagamentos-online/me', { metodo: 'POST', corpo: { cobrancaId: pagavel.cobrancaId, forma } });
      const link = pagamento.linkDePagamento ?? '';
      // O simulado é uma tela do próprio app; a empresa de verdade, um endereço de fora.
      if (link.startsWith('/')) navegar(link);
      else paginaExterna.abrir(link);
    } catch (e) {
      setErro(e);
      setOcupado(false);
    }
  }

  async function simular(p: PagamentoOnline) {
    setErro(null);
    setOcupado(true);
    try {
      await chamar<void>(`/api/pagamentos-online/me/${p.id}/simular-pagamento`, { metodo: 'POST' });
      aoMudar();
    } catch (e) {
      setErro(e);
    } finally {
      setOcupado(false);
    }
  }

  return (
    <div className="pagar-pelo-app">
      <div className="acoes">
        {opcoes.pix && !gerados.PIX && <button type="button" disabled={ocupado} onClick={() => gerar('PIX')}>Pagar com Pix</button>}
        {opcoes.boleto && !gerados.BOLETO && <button type="button" className="secundario" disabled={ocupado} onClick={() => gerar('BOLETO')}>Gerar boleto</button>}
        {opcoes.credito && <button type="button" className="secundario" disabled={ocupado} onClick={() => pagarComCartao('CREDITO')}>Cartão de crédito</button>}
        {opcoes.debito && <button type="button" className="secundario" disabled={ocupado} onClick={() => pagarComCartao('DEBITO')}>Cartão de débito</button>}
      </div>
      {gerados.PIX && (
        <Gerado titulo="Pix copia e cola" codigo={gerados.PIX.pixCopiaECola ?? ''} pagamento={gerados.PIX} qr
                aviso={`Vale até ${ultimoMinuto(gerados.PIX.validoAte)}. No app do banco, leia o QR code (de outro aparelho) ou escolha Pix copia e cola.`} />
      )}
      {gerados.BOLETO && (
        <Gerado titulo="Linha digitável do boleto" codigo={gerados.BOLETO.boletoLinhaDigitavel ?? ''} pagamento={gerados.BOLETO}
                aviso={`Pague até ${ultimoMinuto(gerados.BOLETO.validoAte)}. O boleto pode levar até 3 dias úteis para ser confirmado.`} />
      )}
      {(gerados.PIX || gerados.BOLETO) && (
        <div className="acoes">
          <button type="button" className="secundario" disabled={ocupado} onClick={aoMudar}>Já paguei: atualizar</button>
          {opcoes.simulado && [gerados.PIX, gerados.BOLETO].filter((p) => p !== undefined).map((p) => (
            <button key={p.id} type="button" className="secundario" disabled={ocupado} onClick={() => simular(p)}>
              Simular pagamento do {p.forma === 'PIX' ? 'Pix' : 'boleto'} (teste)
            </button>
          ))}
        </div>
      )}
      <MensagemDeErro erro={erro} />
    </div>
  );
}
