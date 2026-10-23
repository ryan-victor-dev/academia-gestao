import { useCallback, useEffect, useState } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import type { Pagina } from '../../componentes/Paginacao';
import { dataHora } from '../../formato';
import { chamar, chamarComVersao, ErroDaApi } from '../../sessao/api';
import { useUsuario } from '../../sessao/contexto';
import { EditorDeFicha } from './EditorDeFicha';
import type { Ficha } from './tipos';
import { VerFicha } from './VerFicha';

/**
 * O treino do aluno na página dele: a ficha atual e o histórico. O professor e o ADMIN montam
 * uma ficha nova (a atual vai para o histórico) ou corrigem a atual; a recepção só consulta.
 */
export function TreinoDoAluno({ alunoId, alunoAtivo }: { alunoId: string; alunoAtivo: boolean }) {
  const usuario = useUsuario();
  const monta = usuario.perfil === 'ADMIN' || usuario.perfil === 'INSTRUTOR';
  const [atual, setAtual] = useState<Ficha | null | 'nenhuma'>(null);
  const [versao, setVersao] = useState<string | null>(null);
  const [historico, setHistorico] = useState<Ficha[]>([]);
  const [verHistorico, setVerHistorico] = useState(false);
  const [editando, setEditando] = useState<'nova' | 'correcao' | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  const carregar = useCallback(async () => {
    try {
      const { dados, versao } = await chamarComVersao<Ficha>(`/api/fichas/atual?alunoId=${alunoId}`);
      setAtual(dados);
      setVersao(versao);
    } catch (e) {
      if (e instanceof ErroDaApi && e.status === 404) setAtual('nenhuma');
      else setErro(e);
    }
  }, [alunoId]);

  useEffect(() => { void carregar(); }, [carregar]);

  async function abrirHistorico() {
    try {
      const pagina = await chamar<Pagina<Ficha>>(`/api/fichas?alunoId=${alunoId}&tamanho=20`);
      setHistorico(pagina.conteudo.filter((f) => !f.atual));
      setVerHistorico(true);
    } catch (e) {
      setErro(e);
    }
  }

  return (
    <section className="secao">
      <div className="cabecalho">
        <h2>Treino</h2>
        {monta && alunoAtivo && !editando && (
          <button type="button" onClick={() => setEditando('nova')}>Montar ficha nova</button>
        )}
      </div>
      <MensagemDeErro erro={erro} />

      {editando === 'nova' && (
        <EditorDeFicha titulo="Ficha nova" base={atual && atual !== 'nenhuma' ? atual : undefined}
                       aoCancelar={() => setEditando(null)}
                       aoSalvar={async (corpo) => {
                         await chamar('/api/fichas', { metodo: 'POST', corpo: { alunoId, ...corpo } });
                         setEditando(null);
                         setVerHistorico(false);
                         await carregar();
                       }} />
      )}
      {editando === 'correcao' && atual && atual !== 'nenhuma' && (
        <EditorDeFicha titulo="Corrigir a ficha atual" base={atual} correcao aoCancelar={() => setEditando(null)}
                       aoSalvar={async (corpo) => {
                         try {
                           const salvo = await chamarComVersao<Ficha>(`/api/fichas/${atual.id}`,
                             { metodo: 'PUT', corpo, versao: versao ?? undefined });
                           setAtual(salvo.dados);
                           setVersao(salvo.versao);
                           setEditando(null);
                         } catch (e) {
                           if (e instanceof ErroDaApi && e.status === 412) {
                             throw new ErroDaApi(412, 'Outra pessoa corrigiu esta ficha enquanto você editava. '
                               + 'Cancele e abra de novo para ver a versão atual.');
                           }
                           throw e;
                         }
                       }} />
      )}

      {!editando && atual === 'nenhuma' && <p className="suave">Nenhuma ficha montada.</p>}
      {!editando && atual && atual !== 'nenhuma' && (
        <>
          <VerFicha ficha={atual} />
          {monta && <button type="button" className="secundario" onClick={() => setEditando('correcao')}>Corrigir</button>}
        </>
      )}

      {!editando && (verHistorico ? (
        <div className="historico">
          <h3>Fichas anteriores</h3>
          {historico.length === 0 && <p className="suave">Nenhuma ficha anterior.</p>}
          {historico.map((f) => (
            <details key={f.id}>
              <summary>Montada em {dataHora(f.montadaEm)}</summary>
              <VerFicha ficha={f} />
            </details>
          ))}
          <button type="button" className="secundario" onClick={() => setVerHistorico(false)}>Fechar histórico</button>
        </div>
      ) : atual !== null && <button type="button" className="secundario" onClick={abrirHistorico}>Fichas anteriores</button>)}
    </section>
  );
}
