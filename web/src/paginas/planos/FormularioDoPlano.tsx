import { useState, type FormEvent } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import type { Plano } from './ListaDePlanos';

export interface DadosDoPlano {
  nome: string;
  valorMensal: number;
  duracaoMeses: number;
  fidelidade: boolean;
  percentualMulta: number | null;
  formaPagamento: 'MENSAL' | 'A_VISTA';
}

/** Criar ou editar um plano (ADMIN). Sem fidelidade, não há multa. */
export function FormularioDoPlano({ inicial, aoSalvar, aoCancelar }: {
  inicial?: Plano;
  aoSalvar: (dados: DadosDoPlano) => Promise<void>;
  aoCancelar: () => void;
}) {
  const [nome, setNome] = useState(inicial?.nome ?? '');
  const [valor, setValor] = useState(inicial ? String(inicial.valorMensal) : '');
  const [duracao, setDuracao] = useState(inicial ? String(inicial.duracaoMeses) : '1');
  const [fidelidade, setFidelidade] = useState(inicial?.fidelidade ?? false);
  const [multa, setMulta] = useState(inicial?.percentualMulta != null ? String(inicial.percentualMulta) : '');
  const [forma, setForma] = useState<DadosDoPlano['formaPagamento']>(inicial?.formaPagamento ?? 'MENSAL');
  const [erro, setErro] = useState<unknown>(null);
  const [enviando, setEnviando] = useState(false);

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    setErro(null);
    setEnviando(true);
    try {
      await aoSalvar({ nome: nome.trim(), valorMensal: Number(valor), duracaoMeses: Number(duracao), fidelidade,
        percentualMulta: fidelidade ? Number(multa) : null, formaPagamento: forma });
    } catch (e) {
      setErro(e);
    } finally {
      setEnviando(false);
    }
  }

  return (
    <form className="formulario acao" onSubmit={enviar} aria-label={inicial ? 'Editar plano' : 'Novo plano'} noValidate>
      <h3>{inicial ? `Editar ${inicial.nome}` : 'Novo plano'}</h3>
      <label>Nome<input value={nome} maxLength={80} onChange={(e) => setNome(e.target.value)} /></label>
      <label>Valor por mês (R$)<input type="number" min="0.01" step="0.01" inputMode="decimal" value={valor} onChange={(e) => setValor(e.target.value)} /></label>
      <label>Duração (meses)<input type="number" min="1" step="1" value={duracao} onChange={(e) => setDuracao(e.target.value)} /></label>
      <label>Pagamento
        <select aria-label="Pagamento" value={forma} onChange={(e) => setForma(e.target.value as DadosDoPlano['formaPagamento'])}>
          <option value="MENSAL">Mensal</option>
          <option value="A_VISTA">À vista</option>
        </select>
      </label>
      <label className="marcar"><input type="checkbox" checked={fidelidade} onChange={(e) => setFidelidade(e.target.checked)} />Com fidelidade</label>
      {fidelidade && (
        <label>Multa por sair antes (% do saldo restante)
          <input type="number" min="0" max="100" step="0.01" value={multa} onChange={(e) => setMulta(e.target.value)} />
        </label>
      )}
      {inicial && <p className="suave">As matrículas já feitas continuam com o preço e as regras de quando foram feitas.</p>}
      <MensagemDeErro erro={erro} rotulos={{ nome: 'Nome', valorMensal: 'Valor por mês', duracaoMeses: 'Duração',
        percentualMulta: 'Multa', formaPagamento: 'Pagamento' }} />
      <div className="acoes">
        <button type="submit" disabled={enviando || !nome.trim() || !valor || !duracao || (fidelidade && !multa)}>
          {enviando ? 'Salvando…' : 'Salvar'}
        </button>
        <button type="button" className="secundario" onClick={aoCancelar}>Cancelar</button>
      </div>
    </form>
  );
}
