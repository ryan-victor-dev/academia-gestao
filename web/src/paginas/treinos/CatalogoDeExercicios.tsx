import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Excluir } from '../../componentes/Excluir';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { chamar, chamarComVersao, ErroDaApi } from '../../sessao/api';
import { useUsuario } from '../../sessao/contexto';
import { NOME_DO_GRUPO, type Exercicio, type GrupoMuscular } from './tipos';

interface DadosDoExercicio { nome: string; grupoMuscular: GrupoMuscular | ''; observacao: string }

/**
 * Catálogo de exercícios (professor e ADMIN): o que entra nas fichas. Nome único entre os ativos.
 * Só o ADMIN exclui, e só o que nunca entrou numa ficha (ADR 0043).
 */
export function CatalogoDeExercicios() {
  const admin = useUsuario().perfil === 'ADMIN';
  const [grupo, setGrupo] = useState<GrupoMuscular | ''>('');
  const [inativos, setInativos] = useState(false);
  const [lista, setLista] = useState<Exercicio[] | null>(null);
  const [editando, setEditando] = useState<{ exercicio: Exercicio; versao: string | null } | 'novo' | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  const carregar = useCallback(async () => {
    const parametros = new URLSearchParams({ incluirInativos: String(inativos) });
    if (grupo) parametros.set('grupoMuscular', grupo);
    try {
      setLista(await chamar<Exercicio[]>(`/api/exercicios?${parametros}`));
    } catch (e) {
      setErro(e);
    }
  }, [grupo, inativos]);

  useEffect(() => { void carregar(); }, [carregar]);

  async function abrirEdicao(id: string) {
    setErro(null);
    try {
      const { dados, versao } = await chamarComVersao<Exercicio>(`/api/exercicios/${id}`);
      setEditando({ exercicio: dados, versao });
    } catch (e) {
      setErro(e);
    }
  }

  async function mudarSituacao(e: Exercicio) {
    setErro(null);
    try {
      await chamar(`/api/exercicios/${e.id}/${e.ativo ? 'inativar' : 'reativar'}`, { metodo: 'POST' });
      await carregar();
    } catch (falha) {
      setErro(falha);
    }
  }

  async function excluir(e: Exercicio) {
    setErro(null);
    try {
      await chamar(`/api/exercicios/${e.id}`, { metodo: 'DELETE' });
      await carregar();
    } catch (falha) {
      setErro(falha);
    }
  }

  return (
    <>
      <div className="cabecalho">
        <h1>Exercícios</h1>
        {!editando && <button type="button" onClick={() => setEditando('novo')}>Novo exercício</button>}
      </div>

      {editando && (
        <FormularioDoExercicio
          inicial={editando === 'novo' ? undefined : editando.exercicio}
          aoCancelar={() => setEditando(null)}
          aoSalvar={async (dados) => {
            if (editando === 'novo') {
              await chamar('/api/exercicios', { metodo: 'POST', corpo: dados });
            } else {
              try {
                await chamar(`/api/exercicios/${editando.exercicio.id}`,
                  { metodo: 'PUT', corpo: dados, versao: editando.versao ?? undefined });
              } catch (e) {
                if (e instanceof ErroDaApi && e.status === 412) {
                  throw new ErroDaApi(412, 'Outra pessoa alterou este exercício. Cancele e abra de novo.');
                }
                throw e;
              }
            }
            setEditando(null);
            await carregar();
          }} />
      )}

      <div className="filtros">
        <label>Grupo muscular
          <select aria-label="Grupo muscular" value={grupo} onChange={(e) => setGrupo(e.target.value as GrupoMuscular | '')}>
            <option value="">Todos</option>
            {Object.entries(NOME_DO_GRUPO).map(([valor, nome]) => <option key={valor} value={valor}>{nome}</option>)}
          </select>
        </label>
        <label className="marcar">
          <input type="checkbox" checked={inativos} onChange={(e) => setInativos(e.target.checked)} />
          Mostrar inativos
        </label>
      </div>
      <MensagemDeErro erro={erro} />
      {lista?.length === 0 && <p className="suave">Nenhum exercício.</p>}
      <ul className="lista">
        {lista?.map((e) => (
          <li key={e.id} className="linha">
            <span className="principal">{e.nome}</span>
            <span className="suave">{NOME_DO_GRUPO[e.grupoMuscular]}</span>
            {!e.ativo && <span className="etiqueta">Inativo</span>}
            <span className="acoes-linha">
              <button type="button" className="secundario" onClick={() => abrirEdicao(e.id)}>Editar</button>
              <button type="button" className="secundario" onClick={() => mudarSituacao(e)}>{e.ativo ? 'Inativar' : 'Reativar'}</button>
              {admin && <Excluir nome={e.nome} aoExcluir={() => excluir(e)} />}
            </span>
          </li>
        ))}
      </ul>
    </>
  );
}

function FormularioDoExercicio({ inicial, aoSalvar, aoCancelar }: {
  inicial?: Exercicio;
  aoSalvar: (dados: { nome: string; grupoMuscular: GrupoMuscular; observacao: string | null }) => Promise<void>;
  aoCancelar: () => void;
}) {
  const [dados, setDados] = useState<DadosDoExercicio>({
    nome: inicial?.nome ?? '', grupoMuscular: inicial?.grupoMuscular ?? '', observacao: inicial?.observacao ?? '' });
  const [erro, setErro] = useState<unknown>(null);
  const [enviando, setEnviando] = useState(false);

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    setErro(null);
    setEnviando(true);
    try {
      await aoSalvar({ nome: dados.nome.trim(), grupoMuscular: dados.grupoMuscular as GrupoMuscular,
        observacao: dados.observacao.trim() || null });
    } catch (e) {
      setErro(e);
    } finally {
      setEnviando(false);
    }
  }

  return (
    <form className="formulario acao" onSubmit={enviar} aria-label={inicial ? 'Editar exercício' : 'Novo exercício'} noValidate>
      <h3>{inicial ? `Editar ${inicial.nome}` : 'Novo exercício'}</h3>
      <label>Nome<input value={dados.nome} maxLength={80} onChange={(e) => setDados({ ...dados, nome: e.target.value })} /></label>
      <label>Grupo muscular
        <select aria-label="Grupo muscular" value={dados.grupoMuscular} onChange={(e) => setDados({ ...dados, grupoMuscular: e.target.value as GrupoMuscular })}>
          <option value="" disabled>Escolha…</option>
          {Object.entries(NOME_DO_GRUPO).map(([valor, nome]) => <option key={valor} value={valor}>{nome}</option>)}
        </select>
      </label>
      <label className="largo">Observação (como executar)
        <textarea rows={2} maxLength={500} value={dados.observacao} onChange={(e) => setDados({ ...dados, observacao: e.target.value })} />
      </label>
      <MensagemDeErro erro={erro} rotulos={{ nome: 'Nome', grupoMuscular: 'Grupo muscular', observacao: 'Observação' }} />
      <div className="acoes">
        <button type="submit" disabled={enviando || !dados.nome.trim() || !dados.grupoMuscular}>{enviando ? 'Salvando…' : 'Salvar'}</button>
        <button type="button" className="secundario" onClick={aoCancelar}>Cancelar</button>
      </div>
    </form>
  );
}
