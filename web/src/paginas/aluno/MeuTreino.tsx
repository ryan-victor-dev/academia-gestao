import { useEffect, useState } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import type { Pagina } from '../../componentes/Paginacao';
import { data, dataHora } from '../../formato';
import { chamar } from '../../sessao/api';
import type { Ficha } from '../treinos/tipos';
import { VerFicha } from '../treinos/VerFicha';

/**
 * O treino do aluno, no celular: uma divisão por vez (a do dia), com os exercícios em ordem.
 * As fichas anteriores ficam abaixo, recolhidas.
 */
export function MeuTreino() {
  const [fichas, setFichas] = useState<Ficha[] | null>(null);
  const [divisao, setDivisao] = useState(0);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => {
    chamar<Pagina<Ficha>>('/api/fichas/me?tamanho=20').then((p) => setFichas(p.conteudo)).catch(setErro);
  }, []);

  if (!fichas) return erro ? <MensagemDeErro erro={erro} /> : <p className="suave" role="status">Carregando…</p>;
  const atual = fichas.find((f) => f.atual);
  const anteriores = fichas.filter((f) => !f.atual);
  const escolhida = atual?.divisoes[Math.min(divisao, (atual?.divisoes.length ?? 1) - 1)];

  return (
    <>
      <h1>Meu treino</h1>
      {!atual && <p>Você ainda não tem ficha. Fale com o professor.</p>}
      {atual && escolhida && (
        <>
          <p className="suave">
            Ficha de {dataHora(atual.montadaEm)}{atual.revisaoEm && ` · revisão em ${data(atual.revisaoEm)}`}
          </p>
          {atual.divisoes.length > 1 && (
            <div className="abas" role="tablist" aria-label="Divisões">
              {atual.divisoes.map((d, i) => (
                <button key={d.nome} type="button" role="tab" aria-selected={escolhida === d}
                        className={escolhida === d ? 'aba ativa' : 'aba'} onClick={() => setDivisao(i)}>
                  {d.nome}
                </button>
              ))}
            </div>
          )}
          <VerFicha ficha={{ ...atual, divisoes: [escolhida], revisaoEm: null, corrigidaEm: null }} semCabecalho />
        </>
      )}
      {anteriores.length > 0 && (
        <section className="secao">
          <h2>Fichas anteriores</h2>
          {anteriores.map((f) => (
            <details key={f.id}>
              <summary>De {dataHora(f.montadaEm)}</summary>
              <VerFicha ficha={f} />
            </details>
          ))}
        </section>
      )}
    </>
  );
}
