import { useCallback, useEffect, useState } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { cpf, dinheiro } from '../../formato';
import { NOME_DO_PERFIL } from '../../estrutura/menu';
import { chamar, ErroDaApi } from '../../sessao/api';
import { FormularioDaPessoa, type DadosDaPessoa } from './FormularioDaPessoa';
import { NOME_DO_VINCULO, type PessoaDaFolha } from './tipos';

type Editando = { pessoa: PessoaDaFolha } | 'nova' | null;

/**
 * Quem recebe pela folha (ADR 0049, só o ADMIN): a equipe com login aparece sozinha, e quem ainda não está na folha
 * pede o vínculo; quem trabalha sem usar o sistema é cadastrado aqui, sem acesso nenhum.
 */
export function PessoasDaFolha() {
  const [pessoas, setPessoas] = useState<PessoaDaFolha[] | null>(null);
  const [inativos, setInativos] = useState(false);
  const [editando, setEditando] = useState<Editando>(null);
  const [erro, setErro] = useState<unknown>(null);

  const carregar = useCallback(async () => {
    try {
      setPessoas(await chamar<PessoaDaFolha[]>(`/api/folha/pessoas${inativos ? '?incluirInativos=true' : ''}`));
    } catch (e) {
      setErro(e);
    }
  }, [inativos]);
  useEffect(() => { void carregar(); }, [carregar]);

  async function salvar(dados: DadosDaPessoa) {
    if (editando === 'nova') {
      await chamar('/api/folha/pessoas', { metodo: 'POST', corpo: dados });
    } else if (editando && !editando.pessoa.id) {
      await chamar('/api/folha/pessoas', { metodo: 'POST', corpo: { ...dados, usuarioId: editando.pessoa.usuarioId } });
    } else if (editando) {
      try {
        await chamar(`/api/folha/pessoas/${editando.pessoa.id}`, { metodo: 'PUT', corpo: dados, versao: `"${editando.pessoa.versao}"` });
      } catch (e) {
        if (e instanceof ErroDaApi && e.status === 412) {
          throw new ErroDaApi(412, 'Outra pessoa alterou este cadastro. Cancele e abra de novo.');
        }
        throw e;
      }
    }
    setEditando(null);
    await carregar();
  }

  const semVinculo = pessoas?.filter((p) => !p.vinculo).length ?? 0;
  return (
    <section className="secao">
      <div className="cabecalho">
        <h2>Pessoas</h2>
        {!editando && <button type="button" onClick={() => setEditando('nova')}>Nova pessoa sem login</button>}
      </div>
      <p className="suave">Quem tem login (aba Equipe) aparece aqui sozinho. Cadastre aqui só quem trabalha na academia sem
        usar o sistema: essa pessoa não ganha acesso nenhum.</p>
      {editando && (
        <FormularioDaPessoa inicial={editando === 'nova' ? undefined : editando.pessoa}
                            semLogin={editando === 'nova' || !editando.pessoa.usuarioId}
                            aoSalvar={salvar} aoCancelar={() => setEditando(null)} />
      )}
      <label className="marcar">
        <input type="checkbox" checked={inativos} onChange={(e) => setInativos(e.target.checked)} />
        Mostrar inativos
      </label>
      <MensagemDeErro erro={erro} />
      {semVinculo > 0 && (
        <p className="aviso" role="status">{semVinculo === 1 ? '1 pessoa da equipe ainda não tem' : `${semVinculo} pessoas da equipe ainda não têm`} o
          vínculo definido.</p>
      )}
      {pessoas?.length === 0 && <p className="suave">Ninguém na folha.</p>}
      <ul className="cartoes">
        {pessoas?.map((p) => (
          <li key={p.id ?? p.usuarioId} className="cartao-item">
            <div className="cabecalho">
              <strong>{p.nome}</strong>
              <span className="etiqueta">{p.perfil ? NOME_DO_PERFIL[p.perfil] : 'Sem login'}</span>
              {!p.ativo && <span className="etiqueta">Inativa</span>}
            </div>
            {p.vinculo ? (
              <p>{NOME_DO_VINCULO[p.vinculo]} · {p.valorCombinado != null ? `${dinheiro(p.valorCombinado)} por mês` : 'sem valor fixo'}</p>
            ) : <p><span className="etiqueta alerta">Sem vínculo</span></p>}
            {p.cpf && <p className="suave">CPF {cpf(p.cpf)}</p>}
            {!editando && (
              <div className="acoes">
                <button type="button" className={p.vinculo ? 'secundario' : undefined} onClick={() => setEditando({ pessoa: p })}>
                  {p.vinculo ? 'Editar' : 'Definir vínculo'}
                </button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
