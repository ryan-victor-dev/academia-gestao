import { useCallback, useEffect, useState } from 'react';
import { FormularioDeAcao } from '../../componentes/FormularioDeAcao';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { data, dataHora, dinheiro, hoje } from '../../formato';
import { chamar, ErroDaApi } from '../../sessao/api';
import { useUsuario } from '../../sessao/contexto';
import { NOME_DO_MEIO, type Meio } from '../cobrancas/tipos';
import { RelatorioDoCaixa } from './RelatorioDoCaixa';

interface Forma { forma: Meio; recebido: number; pagamentos: number; estornado: number; estornos: number; liquido: number }
interface Fechamento { contado: number; esperado: number; diferenca: number; justificativa: string | null; em: string }

export interface Caixa {
  id: string;
  dia: string;
  troco: number;
  situacao: 'ABERTO' | 'FECHADO';
  abertoEm: string;
  dinheiroOculto: boolean;
  movimento: { formas: Forma[]; total: { recebido: number; estornado: number; liquido: number } };
  fechamentos: Fechamento[];
  reaberturas: Array<{ motivo: string; em: string }>;
}

/**
 * O caixa do dia (ADR 0031): abrir com o troco, ver o movimento por forma e fechar contando o
 * dinheiro da gaveta. Com o caixa aberto, a recepção não vê o dinheiro (contagem às cegas).
 * O ADMIN vê qualquer dia e reabre um dia fechado.
 */
export function CaixaDoDia() {
  const usuario = useUsuario();
  const admin = usuario.perfil === 'ADMIN';
  const [dia, setDia] = useState(hoje());
  const [caixa, setCaixa] = useState<Caixa | null | 'nenhum'>(null);
  const [abertos, setAbertos] = useState<Array<{ dia: string }>>([]);
  const [reabrindo, setReabrindo] = useState(false);
  const [erro, setErro] = useState<unknown>(null);

  const carregar = useCallback(async () => {
    setErro(null);
    try {
      setAbertos(await chamar<Array<{ dia: string }>>('/api/caixas/abertos'));
      setCaixa(await chamar<Caixa>(`/api/caixas/${dia}`));
    } catch (e) {
      if (e instanceof ErroDaApi && e.status === 404) setCaixa('nenhum');
      else setErro(e);
    }
  }, [dia]);

  useEffect(() => { void carregar(); }, [carregar]);

  async function enviar(caminho: string, corpo: unknown) {
    setErro(null);
    try {
      setCaixa(await chamar<Caixa>(caminho, { metodo: 'POST', corpo }));
      setReabrindo(false);
      setAbertos(await chamar<Array<{ dia: string }>>('/api/caixas/abertos'));
    } catch (e) {
      setErro(e);
    }
  }

  const esquecidos = abertos.filter((a) => a.dia < hoje());

  return (
    <>
      <div className="cabecalho">
        <h1>Caixa de {data(dia)}{dia === hoje() ? ' (hoje)' : ''}</h1>
        {admin && (
          <label className="em-linha">Ver o dia
            <input type="date" value={dia} max={hoje()} onChange={(e) => e.target.value && setDia(e.target.value)} />
          </label>
        )}
      </div>

      {esquecidos.map((a) => (
        <p key={a.dia} className="aviso" role="status">
          O caixa de {data(a.dia)} ainda está aberto: feche-o antes de abrir o de hoje.{' '}
          {a.dia !== dia && <button type="button" className="secundario" onClick={() => setDia(a.dia)}>Ver e fechar</button>}
        </p>
      ))}

      <MensagemDeErro erro={erro} rotulos={{ troco: 'Troco', contado: 'Contado', justificativa: 'Justificativa' }} />

      {caixa === 'nenhum' && (
        dia === hoje() ? (
          <FormularioDeAcao titulo="Abrir o caixa de hoje" rotulo="Abrir caixa"
                            aoEnviar={(f) => enviar('/api/caixas', { troco: Number(f.get('troco')) })}>
            <label>Troco na gaveta (R$)<input name="troco" type="number" min="0" step="0.01" required inputMode="decimal" /></label>
          </FormularioDeAcao>
        ) : <p className="suave">Nenhum caixa neste dia.</p>
      )}

      {caixa && caixa !== 'nenhum' && (
        <>
          <dl className="dados">
            <dt>Situação</dt><dd>{caixa.situacao === 'ABERTO' ? 'Aberto' : 'Fechado'}</dd>
            <dt>Aberto em</dt><dd>{dataHora(caixa.abertoEm)}</dd>
            <dt>Troco</dt><dd>{dinheiro(caixa.troco)}</dd>
          </dl>

          <h2>Movimento</h2>
          {caixa.dinheiroOculto && (
            <p className="suave">O dinheiro só aparece depois do fechamento: a contagem é às cegas.</p>
          )}
          <table className="tabela">
            <thead><tr><th>Forma</th><th>Recebido</th><th>Estornado</th><th>Líquido</th></tr></thead>
            <tbody>
              {caixa.movimento.formas.map((f) => (
                <tr key={f.forma}>
                  <td>{NOME_DO_MEIO[f.forma]}</td>
                  <td>{dinheiro(f.recebido)} <span className="suave">({f.pagamentos})</span></td>
                  <td>{dinheiro(f.estornado)} <span className="suave">({f.estornos})</span></td>
                  <td>{dinheiro(f.liquido)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr><th>Total{caixa.dinheiroOculto ? ' (sem o dinheiro)' : ''}</th><td>{dinheiro(caixa.movimento.total.recebido)}</td>
                <td>{dinheiro(caixa.movimento.total.estornado)}</td><td>{dinheiro(caixa.movimento.total.liquido)}</td></tr>
            </tfoot>
          </table>

          {caixa.situacao === 'ABERTO' && (
            <FormularioDeAcao titulo="Fechar o caixa" rotulo="Fechar caixa"
                              aoEnviar={(f) => enviar(`/api/caixas/${caixa.dia}/fechar`,
                                { contado: Number(f.get('contado')), justificativa: f.get('justificativa') || null })}>
              <p>Conte <strong>todo o dinheiro da gaveta</strong>, com o troco.</p>
              <label>Dinheiro contado (R$)<input name="contado" type="number" min="0" step="0.01" required inputMode="decimal" /></label>
              <label className="largo">Justificativa (se o contado não bater, é obrigatória)
                <textarea name="justificativa" rows={2} maxLength={500} />
              </label>
            </FormularioDeAcao>
          )}

          {caixa.fechamentos.length > 0 && (
            <>
              <h2>Fechamentos</h2>
              <ul className="cartoes">
                {caixa.fechamentos.map((f) => (
                  <li key={f.em} className="cartao-item">
                    <p>{dataHora(f.em)}: contado {dinheiro(f.contado)}, esperado {dinheiro(f.esperado)},
                      {' '}<strong className={f.diferenca === 0 ? 'sucesso' : 'erro'}>diferença {dinheiro(f.diferenca)}</strong></p>
                    {f.justificativa && <p className="suave">Justificativa: {f.justificativa}</p>}
                  </li>
                ))}
              </ul>
            </>
          )}
          {caixa.reaberturas.map((r) => <p key={r.em} className="suave">Reaberto em {dataHora(r.em)}: {r.motivo}</p>)}

          {admin && caixa.situacao === 'FECHADO' && (reabrindo ? (
            <FormularioDeAcao titulo="Reabrir o dia" rotulo="Reabrir" aoCancelar={() => setReabrindo(false)}
                              aoEnviar={(f) => enviar(`/api/caixas/${caixa.dia}/reabrir`, { motivo: f.get('motivo') })}>
              <label>Motivo<input name="motivo" required maxLength={500} /></label>
            </FormularioDeAcao>
          ) : <button type="button" className="secundario" onClick={() => setReabrindo(true)}>Reabrir o dia</button>)}
        </>
      )}

      {admin && <RelatorioDoCaixa />}
    </>
  );
}
