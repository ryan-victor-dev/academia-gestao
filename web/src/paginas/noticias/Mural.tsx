import { useCallback, useEffect, useState } from 'react';
import { Excluir } from '../../componentes/Excluir';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { Paginacao, type Pagina } from '../../componentes/Paginacao';
import { dataHora } from '../../formato';
import { chamar } from '../../sessao/api';
import { useUsuario } from '../../sessao/contexto';
import { FormularioDePublicacao, type PedidoDePublicacao } from './FormularioDePublicacao';
import { NOME_DO_TIPO, type Publicacao, type TipoDePublicacao } from './tipos';

type Filtro = '' | TipoDePublicacao;
const FILTROS: Array<[Filtro, string]> = [['', 'Todas'], ['NOTICIA', 'Notícias'], ['COMUNICADO', 'Comunicados'], ['EVENTO', 'Eventos']];

/** Quando e onde o evento acontece. */
function QuandoEOnde({ p }: { p: Publicacao }) {
  if (p.tipo !== 'EVENTO') return null;
  return (
    <p className="evento-quando">
      <strong>{dataHora(p.eventoInicio)}</strong>{p.eventoFim && <> até <strong>{dataHora(p.eventoFim)}</strong></>}
      {p.eventoLocal && <> · {p.eventoLocal}</>}
    </p>
  );
}

/**
 * Notícias, comunicados e eventos da academia (ADR 0047): a página inicial de todos. O ADMIN e a recepção
 * publicam, editam e excluem. O texto aparece como texto (nunca como HTML), e os links abrem fora do app.
 */
export function Mural() {
  const usuario = useUsuario();
  const publica = usuario.perfil === 'ADMIN' || usuario.perfil === 'RECEPCAO';
  const [filtro, setFiltro] = useState<Filtro>('');
  const [numero, setNumero] = useState(0);
  const [pagina, setPagina] = useState<Pagina<Publicacao> | null>(null);
  const [eventos, setEventos] = useState<Publicacao[]>([]);
  const [acao, setAcao] = useState<{ tipo: 'nova' } | { tipo: 'editar'; publicacao: Publicacao } | null>(null);
  const [versao, setVersao] = useState(0);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => {
    let atual = true;
    const parametros = new URLSearchParams({ pagina: String(numero), tamanho: '10' });
    if (filtro) parametros.set('tipo', filtro);
    chamar<Pagina<Publicacao>>(`/api/publicacoes?${parametros}`)
      .then((p) => { if (atual) setPagina(p); }).catch((e) => { if (atual) setErro(e); });
    chamar<Publicacao[]>('/api/publicacoes/proximos-eventos')
      .then((e) => { if (atual) setEventos(e); }).catch((e) => { if (atual) setErro(e); });
    return () => { atual = false; };
  }, [filtro, numero, versao]);

  const executar = useCallback(async (fazer: () => Promise<unknown>) => {
    setErro(null);
    try {
      await fazer();
      setAcao(null);
      setVersao((v) => v + 1);
    } catch (e) {
      setErro(e);
    }
  }, []);

  const publicar = (pedido: PedidoDePublicacao) => executar(() => chamar('/api/publicacoes', { metodo: 'POST', corpo: pedido }));

  return (
    <section className="mural" aria-label="Notícias e eventos">
      <div className="cabecalho">
        <h2>Notícias e eventos</h2>
        {publica && !acao && <button type="button" onClick={() => setAcao({ tipo: 'nova' })}>Nova publicação</button>}
      </div>
      <MensagemDeErro erro={erro} rotulos={{ titulo: 'Título', texto: 'Texto', links: 'Links', eventoInicio: 'Começa em',
        eventoFim: 'Termina em', eventoLocal: 'Local', tipo: 'Tipo' }} />
      {acao?.tipo === 'nova' && <FormularioDePublicacao aoEnviar={publicar} aoCancelar={() => setAcao(null)} />}

      {eventos.length > 0 && (
        <div className="proximos-eventos">
          <h3>Próximos eventos</h3>
          <ul>
            {eventos.map((e) => (
              <li key={e.id}><strong>{e.titulo}</strong> · {dataHora(e.eventoInicio)}{e.eventoLocal && ` · ${e.eventoLocal}`}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="abas" role="tablist" aria-label="Tipo de publicação">
        {FILTROS.map(([valor, nome]) => (
          <button key={nome} type="button" role="tab" aria-selected={filtro === valor} className={filtro === valor ? 'aba ativa' : 'aba'}
                  onClick={() => { setFiltro(valor); setNumero(0); }}>{nome}</button>
        ))}
      </div>
      {pagina?.totalElementos === 0 && <p className="suave">Nada publicado ainda.</p>}
      <ul className="cartoes">
        {pagina?.conteudo.map((p) => (
          <li key={p.id} className="cartao-item publicacao">
            {acao?.tipo === 'editar' && acao.publicacao.id === p.id ? (
              <FormularioDePublicacao inicial={p} aoCancelar={() => setAcao(null)}
                                      aoEnviar={(pedido) => executar(() => chamar(`/api/publicacoes/${p.id}`, { metodo: 'PUT', corpo: pedido }))} />
            ) : (
              <article>
                <div className="cabecalho">
                  <h3>{p.titulo}</h3>
                  <span className={`etiqueta publicacao-${p.tipo.toLowerCase()}`}>{NOME_DO_TIPO[p.tipo]}</span>
                </div>
                <QuandoEOnde p={p} />
                <p className="texto">{p.texto}</p>
                {p.links.length > 0 && (
                  <ul className="links">
                    {p.links.map((l, i) => (
                      <li key={i}><a href={l.url} target="_blank" rel="noopener noreferrer">{l.rotulo ?? l.url}</a></li>
                    ))}
                  </ul>
                )}
                <p className="suave">Publicado por {p.autorNome} em {dataHora(p.criadaEm)}{p.editadaEm && ` · editado em ${dataHora(p.editadaEm)}`}</p>
                {publica && (
                  <div className="acoes">
                    <button type="button" className="secundario" onClick={() => setAcao({ tipo: 'editar', publicacao: p })}>Editar</button>
                    <Excluir nome={p.titulo} aoExcluir={() => executar(() => chamar(`/api/publicacoes/${p.id}`, { metodo: 'DELETE' }))} />
                  </div>
                )}
              </article>
            )}
          </li>
        ))}
      </ul>
      {pagina && <Paginacao pagina={pagina} aoMudar={setNumero} />}
    </section>
  );
}
