import { useState, type FormEvent } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { data, dinheiro, hoje } from '../../formato';
import { chamar } from '../../sessao/api';

interface Total { recebido: number; estornado: number; liquido: number }
interface Dia {
  dia: string;
  situacao: 'ABERTO' | 'FECHADO' | null;
  troco: number | null;
  movimento: { total: Total };
  fechamentos: Array<{ diferenca: number; justificativa: string | null }>;
}
interface Relatorio { de: string; ate: string; dias: Dia[]; total: { total: Total }; diferencaDosFechados: number }

const primeiroDoMes = () => `${hoje().slice(0, 8)}01`;

/** Relatório do caixa por período (só o ADMIN; até 366 dias): cada dia, o total e as diferenças. */
export function RelatorioDoCaixa() {
  const [de, setDe] = useState(primeiroDoMes());
  const [ate, setAte] = useState(hoje());
  const [relatorio, setRelatorio] = useState<Relatorio | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  async function gerar(evento: FormEvent) {
    evento.preventDefault();
    setErro(null);
    try {
      setRelatorio(await chamar<Relatorio>(`/api/caixas/relatorio?de=${de}&ate=${ate}`));
    } catch (e) {
      setErro(e);
    }
  }

  return (
    <section className="secao">
      <h2>Relatório do período</h2>
      <form className="filtros" onSubmit={gerar} aria-label="Relatório do caixa">
        <label>De<input type="date" value={de} max={ate} onChange={(e) => setDe(e.target.value)} /></label>
        <label>Até<input type="date" value={ate} max={hoje()} onChange={(e) => setAte(e.target.value)} /></label>
        <button type="submit">Gerar</button>
      </form>
      <MensagemDeErro erro={erro} rotulos={{ de: 'De', ate: 'Até' }} />
      {relatorio && (
        <>
          {relatorio.dias.length === 0 && <p className="suave">Nenhum movimento no período.</p>}
          {relatorio.dias.length > 0 && (
            <table className="tabela">
              <thead><tr><th>Dia</th><th>Recebido</th><th>Estornado</th><th>Líquido</th><th>Diferença</th></tr></thead>
              <tbody>
                {relatorio.dias.map((d) => {
                  const ultimo = d.fechamentos.at(-1);
                  return (
                    <tr key={d.dia}>
                      <td>{data(d.dia)}{d.situacao === 'ABERTO' && <span className="etiqueta alerta">aberto</span>}</td>
                      <td>{dinheiro(d.movimento.total.recebido)}</td>
                      <td>{dinheiro(d.movimento.total.estornado)}</td>
                      <td>{dinheiro(d.movimento.total.liquido)}</td>
                      <td className={ultimo && ultimo.diferenca !== 0 ? 'erro' : ''} title={ultimo?.justificativa ?? undefined}>
                        {ultimo ? dinheiro(ultimo.diferenca) : '—'}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
              <tfoot>
                <tr><th>Total</th><td>{dinheiro(relatorio.total.total.recebido)}</td><td>{dinheiro(relatorio.total.total.estornado)}</td>
                  <td>{dinheiro(relatorio.total.total.liquido)}</td><td>{dinheiro(relatorio.diferencaDosFechados)}</td></tr>
              </tfoot>
            </table>
          )}
        </>
      )}
    </section>
  );
}
