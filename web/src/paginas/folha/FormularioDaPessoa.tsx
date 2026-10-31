import { useState, type FormEvent } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { cpf as formatarCpf } from '../../formato';
import { NOME_DO_VINCULO, type PessoaDaFolha, type Vinculo } from './tipos';

export interface DadosDaPessoa {
  nome?: string;
  cpf?: string;
  vinculo: Vinculo;
  valorCombinado: number | null;
  ativo?: boolean;
}

/**
 * O combinado com a pessoa (ADR 0049): vínculo e valor fixo por mês (vazio: sem valor fixo). Quem não tem login também
 * tem nome, CPF e, na edição, a situação; o nome e o CPF de quem tem login são os do login.
 */
export function FormularioDaPessoa({ inicial, semLogin, aoSalvar, aoCancelar }: {
  inicial?: PessoaDaFolha;
  semLogin: boolean;
  aoSalvar: (dados: DadosDaPessoa) => Promise<void>;
  aoCancelar: () => void;
}) {
  const [nome, setNome] = useState(inicial?.nome ?? '');
  const [cpf, setCpf] = useState(inicial?.cpf ? formatarCpf(inicial.cpf) : '');
  const [vinculo, setVinculo] = useState<Vinculo | ''>(inicial?.vinculo ?? '');
  const [valor, setValor] = useState(inicial?.valorCombinado != null ? String(inicial.valorCombinado) : '');
  const [ativo, setAtivo] = useState(inicial?.ativo ?? true);
  const [erro, setErro] = useState<unknown>(null);
  const [enviando, setEnviando] = useState(false);
  const editandoSemLogin = semLogin && inicial?.id;

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    if (!vinculo) return;
    setErro(null);
    setEnviando(true);
    try {
      await aoSalvar({
        ...(semLogin && { nome: nome.trim(), cpf }),
        vinculo, valorCombinado: valor ? Number(valor) : null,
        ...(editandoSemLogin && { ativo }),
      });
    } catch (e) {
      setErro(e);
    } finally {
      setEnviando(false);
    }
  }

  const titulo = inicial?.id ? `Editar ${inicial.nome}` : inicial ? `Incluir ${inicial.nome} na folha` : 'Nova pessoa sem login';
  return (
    <form className="formulario acao" onSubmit={enviar} aria-label={titulo} noValidate>
      <h3>{titulo}</h3>
      {semLogin ? (
        <>
          <label>Nome<input value={nome} maxLength={150} onChange={(e) => setNome(e.target.value)} /></label>
          <label>CPF<input value={cpf} maxLength={14} inputMode="numeric" autoComplete="off" onChange={(e) => setCpf(e.target.value)} /></label>
        </>
      ) : <p className="suave">O nome, o CPF e a situação são os do login (aba Equipe).</p>}
      <label>Vínculo
        <select aria-label="Vínculo" value={vinculo} onChange={(e) => setVinculo(e.target.value as Vinculo)}>
          <option value="" disabled>Escolha…</option>
          {Object.entries(NOME_DO_VINCULO).map(([v, n]) => <option key={v} value={v}>{n}</option>)}
        </select>
      </label>
      <label>Valor combinado por mês (R$)
        <input type="number" min="0.01" step="0.01" inputMode="decimal" value={valor} onChange={(e) => setValor(e.target.value)} />
      </label>
      <p className="suave">Deixe vazio se não houver valor fixo (quem recebe por aula, por exemplo). Para o CLT, o valor que a
        academia paga a ele (o líquido do holerite); o sistema não calcula imposto nem encargo.</p>
      {editandoSemLogin && (
        <label className="marcar"><input type="checkbox" checked={ativo} onChange={(e) => setAtivo(e.target.checked)} />Ativa</label>
      )}
      <MensagemDeErro erro={erro} rotulos={{ nome: 'Nome', cpf: 'CPF', vinculo: 'Vínculo', valorCombinado: 'Valor combinado' }} />
      <div className="acoes">
        <button type="submit" disabled={enviando || !vinculo || (semLogin && (!nome.trim() || !cpf.trim()))}>
          {enviando ? 'Salvando…' : 'Salvar'}
        </button>
        <button type="button" className="secundario" onClick={aoCancelar}>Cancelar</button>
      </div>
    </form>
  );
}
