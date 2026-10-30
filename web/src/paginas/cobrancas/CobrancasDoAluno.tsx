import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router';
import { FormularioDeAcao } from '../../componentes/FormularioDeAcao';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { Gerado, ultimoMinuto, type PagamentoOnline } from '../../componentes/PagamentoGerado';
import { data, dataHora, dinheiro } from '../../formato';
import { chamar } from '../../sessao/api';
import { useUsuario } from '../../sessao/contexto';
import { descricao, exigeCodigo, NOME_DA_SITUACAO, NOME_DO_MEIO, NOME_DO_MEIO_DO_BALCAO, type Cobranca, type Lancamento, type MeioDoBalcao } from './tipos';

type Acao = { tipo: 'receber' | 'estornar' | 'cancelar' | 'historico'; id: string };

/** A forma e, no Pix e no cartão, o código da transação do comprovante. */
function FormaDoRecebimento() {
  const [meio, setMeio] = useState('');
  return (
    <>
      <label>Forma de pagamento
        <select name="meio" required value={meio} onChange={(e) => setMeio(e.target.value)} aria-label="Forma de pagamento">
          <option value="" disabled>Escolha…</option>
          {Object.entries(NOME_DO_MEIO_DO_BALCAO).map(([m, nome]) => <option key={m} value={m}>{nome}</option>)}
        </select>
      </label>
      {exigeCodigo(meio) && (
        <>
          <label>Código da transação<input name="codigo" required maxLength={100} autoComplete="off" /></label>
          <p className="suave">{meio === 'PIX' ? 'O ID da transação no comprovante do Pix.'
            : 'O NSU ou o código de autorização no comprovante da maquininha.'}</p>
        </>
      )}
    </>
  );
}

/**
 * Cobranças do aluno (recepção e ADMIN): receber no balcão pelo devido de hoje; o ADMIN também
 * estorna e cancela. Pagar e estornar exigem o caixa de hoje aberto (a API recusa sem ele). Com o
 * pagamento online ligado, o balcão também gera o boleto para o aluno pagar depois (ADR 0047): ele
 * não passa pelo caixa, e a cobrança fica paga quando a empresa avisa.
 */
export function CobrancasDoAluno({ alunoId, atualizar }: { alunoId: string; atualizar: number }) {
  const usuario = useUsuario();
  const admin = usuario.perfil === 'ADMIN';
  const [cobrancas, setCobrancas] = useState<Cobranca[] | null>(null);
  const [acao, setAcao] = useState<Acao | null>(null);
  const [lancamentos, setLancamentos] = useState<Lancamento[]>([]);
  const [comBoleto, setComBoleto] = useState(false);
  const [boletos, setBoletos] = useState<Record<string, PagamentoOnline>>({});
  const [gerando, setGerando] = useState<string | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  // Sem empresa ou com o boleto desligado, o botão nem aparece. Se a pergunta falhar, fica sem boleto.
  useEffect(() => {
    let atual = true;
    chamar<{ boleto: boolean }>('/api/pagamentos-online/balcao')
      .then((o) => { if (atual) setComBoleto(o.boleto); }).catch(() => { if (atual) setComBoleto(false); });
    return () => { atual = false; };
  }, []);

  async function gerarBoleto(cobrancaId: string) {
    setErro(null);
    setGerando(cobrancaId);
    try {
      const boleto = await chamar<PagamentoOnline>('/api/pagamentos-online/balcao/boleto',
        { metodo: 'POST', corpo: { alunoId, cobrancaId } });
      setBoletos((b) => ({ ...b, [cobrancaId]: boleto }));
    } catch (e) {
      setErro(e);
    } finally {
      setGerando(null);
    }
  }

  const carregar = useCallback(async () => {
    try {
      setCobrancas(await chamar<Cobranca[]>(`/api/cobrancas?alunoId=${alunoId}`));
    } catch (e) {
      setErro(e);
    }
  }, [alunoId]);

  // "atualizar" muda quando a matrícula muda (nova, cancelada): as cobranças mudam junto.
  useEffect(() => { void carregar(); }, [carregar, atualizar]);

  async function executar(caminho: string, corpo: unknown) {
    setErro(null);
    try {
      await chamar(caminho, { metodo: 'POST', corpo });
      setAcao(null);
      await carregar();
    } catch (e) {
      setErro(e);
    }
  }

  async function verHistorico(id: string) {
    setErro(null);
    try {
      setLancamentos(await chamar<Lancamento[]>(`/api/cobrancas/${id}/lancamentos`));
      setAcao({ tipo: 'historico', id });
    } catch (e) {
      setErro(e);
    }
  }

  const emAberto = (c: Cobranca) => c.situacao === 'PENDENTE' || c.situacao === 'ATRASADA';
  const semCaixa = erro instanceof Error && /caixa/i.test(erro.message);

  return (
    <section className="secao">
      <h2>Cobranças</h2>
      <MensagemDeErro erro={erro} rotulos={{ valor: 'Valor', meioDePagamento: 'Forma de pagamento', codigoTransacao: 'Código da transação', motivo: 'Motivo' }} />
      {semCaixa && <p><Link to="/caixa">Abrir o caixa de hoje</Link></p>}
      {cobrancas?.length === 0 && <p className="suave">Nenhuma cobrança.</p>}
      <ul className="cartoes">
        {cobrancas?.map((c) => (
          <li key={c.id} className="cartao-item">
            <div className="cabecalho">
              <strong>{descricao(c)}</strong>
              <span className={`etiqueta cobranca-${c.situacao.toLowerCase()}`}>{NOME_DA_SITUACAO[c.situacao]}</span>
            </div>
            <p>Vence em {data(c.vencimento)} · {dinheiro(c.valor)}
              {c.situacao === 'ATRASADA' && ` · ${c.diasDeAtraso} ${c.diasDeAtraso === 1 ? 'dia' : 'dias'} de atraso`}</p>
            {c.devidoHoje && (c.devidoHoje.acrescimo > 0 || c.devidoHoje.juros > 0) && (
              <p>Devido hoje: <strong>{dinheiro(c.devidoHoje.total)}</strong>
                {' '}(acréscimo {dinheiro(c.devidoHoje.acrescimo)}, juros {dinheiro(c.devidoHoje.juros)})</p>
            )}
            {c.pagamento && <p className="suave">Paga em {dataHora(c.pagamento.em)}: {dinheiro(c.pagamento.total)} ({NOME_DO_MEIO[c.pagamento.meio]}{c.pagamento.codigoTransacao && `, código ${c.pagamento.codigoTransacao}`})</p>}
            {c.cancelamento && <p className="suave">Cancelada: {c.cancelamento.motivo}</p>}

            <div className="acoes">
              {emAberto(c) && c.devidoHoje && (
                <button type="button" onClick={() => setAcao({ tipo: 'receber', id: c.id })}>Receber</button>
              )}
              {comBoleto && emAberto(c) && !boletos[c.id] && (
                <button type="button" className="secundario" disabled={gerando === c.id} onClick={() => gerarBoleto(c.id)}>
                  {gerando === c.id ? 'Gerando…' : 'Gerar boleto'}
                </button>
              )}
              {admin && c.situacao === 'PAGA' && (
                <button type="button" className="perigo" onClick={() => setAcao({ tipo: 'estornar', id: c.id })}>Estornar</button>
              )}
              {admin && emAberto(c) && (
                <button type="button" className="perigo" onClick={() => setAcao({ tipo: 'cancelar', id: c.id })}>Cancelar</button>
              )}
              <button type="button" className="secundario" onClick={() => verHistorico(c.id)}>Histórico</button>
            </div>

            {boletos[c.id] && (
              <Gerado titulo="Linha digitável do boleto" codigo={boletos[c.id].boletoLinhaDigitavel ?? ''} pagamento={boletos[c.id]}
                      aviso={`Passe ao aluno: ele paga até ${ultimoMinuto(boletos[c.id].validoAte)} no banco ou no app do banco. `
                        + 'O boleto também aparece no app dele, e a cobrança fica paga quando o banco confirmar (até 3 dias úteis).'} />
            )}
            {acao?.id === c.id && acao.tipo === 'receber' && c.devidoHoje && (
              <FormularioDeAcao titulo="Receber" rotulo={`Receber ${dinheiro(c.devidoHoje.total)}`} aoCancelar={() => setAcao(null)}
                                aoEnviar={(f) => executar(`/api/cobrancas/${c.id}/pagar`, {
                                  valor: c.devidoHoje!.total, meioDePagamento: f.get('meio') as MeioDoBalcao,
                                  ...(exigeCodigo(f.get('meio') as string) && { codigoTransacao: f.get('codigo') }),
                                })}>
                <FormaDoRecebimento />
                <p>Valor exato de hoje: <strong>{dinheiro(c.devidoHoje.total)}</strong></p>
              </FormularioDeAcao>
            )}
            {acao?.id === c.id && (acao.tipo === 'estornar' || acao.tipo === 'cancelar') && (
              <FormularioDeAcao titulo={acao.tipo === 'estornar' ? 'Estornar pagamento' : 'Cancelar cobrança'}
                                rotulo={acao.tipo === 'estornar' ? 'Confirmar estorno' : 'Confirmar cancelamento'}
                                aoCancelar={() => setAcao(null)}
                                aoEnviar={(f) => executar(`/api/cobrancas/${c.id}/${acao.tipo}`, { motivo: f.get('motivo') })}>
                {acao.tipo === 'estornar' && <p className="aviso">O dinheiro sai do caixa de hoje, e a cobrança volta a ser devida.</p>}
                <label>Motivo<input name="motivo" required maxLength={500} /></label>
              </FormularioDeAcao>
            )}
            {acao?.id === c.id && acao.tipo === 'historico' && (
              <div className="historico">
                {lancamentos.length === 0 ? <p className="suave">Nenhum pagamento nem estorno.</p> : (
                  <ul>
                    {lancamentos.map((l) => (
                      <li key={l.id}>{dataHora(l.em)} · {l.tipo === 'PAGAMENTO' ? 'Pagamento' : 'Estorno'} de {dinheiro(l.total)}
                        {' '}({NOME_DO_MEIO[l.meio]}{l.codigoTransacao && `, código ${l.codigoTransacao}`}){l.motivo ? `: ${l.motivo}` : ''}</li>
                    ))}
                  </ul>
                )}
                <button type="button" className="secundario" onClick={() => setAcao(null)}>Fechar histórico</button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
