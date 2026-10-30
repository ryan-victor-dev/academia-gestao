import { useState } from 'react';
import { FormularioDeAcao } from '../../componentes/FormularioDeAcao';
import { instanteDaAcademia, paraCampoDeDataHora } from '../../formato';
import { NOME_DO_TIPO, type LinkDaPublicacao, type Publicacao, type TipoDePublicacao } from './tipos';

const MAXIMO_DE_LINKS = 10;

/** O corpo do pedido (ADR 0047): o mesmo para publicar e editar. */
export interface PedidoDePublicacao {
  tipo: TipoDePublicacao;
  titulo: string;
  texto: string;
  eventoInicio: string | null;
  eventoFim: string | null;
  eventoLocal: string | null;
  links: LinkDaPublicacao[];
}

/**
 * Publicar ou editar uma notícia, um comunicado ou um evento. Só o evento pede quando (na hora da academia)
 * e onde. Os links vão em linhas, até 10; a API só aceita http e https.
 */
export function FormularioDePublicacao({ inicial, aoEnviar, aoCancelar }: {
  inicial?: Publicacao; aoEnviar: (pedido: PedidoDePublicacao) => Promise<void>; aoCancelar: () => void;
}) {
  const [tipo, setTipo] = useState<TipoDePublicacao>(inicial?.tipo ?? 'NOTICIA');
  const [links, setLinks] = useState<LinkDaPublicacao[]>(inicial?.links ?? []);
  const evento = tipo === 'EVENTO';

  function mudarLink(i: number, campo: keyof LinkDaPublicacao, valor: string) {
    setLinks((atuais) => atuais.map((l, j) => (j === i ? { ...l, [campo]: valor } : l)));
  }

  return (
    <FormularioDeAcao titulo={inicial ? 'Editar publicação' : 'Nova publicação'} rotulo={inicial ? 'Salvar' : 'Publicar'}
                      aoCancelar={aoCancelar}
                      aoEnviar={(f) => aoEnviar({
                        tipo,
                        titulo: String(f.get('titulo')),
                        texto: String(f.get('texto')),
                        // Fora do evento, os campos de quando e onde nem existem: f.get dá null.
                        eventoInicio: f.get('eventoInicio') ? instanteDaAcademia(String(f.get('eventoInicio'))) : null,
                        eventoFim: f.get('eventoFim') ? instanteDaAcademia(String(f.get('eventoFim'))) : null,
                        eventoLocal: String(f.get('eventoLocal') ?? '') || null,
                        links: links.map((l) => ({ url: l.url.trim(), rotulo: l.rotulo?.trim() || null })),
                      })}>
      <label>Tipo
        <select aria-label="Tipo" value={tipo} onChange={(e) => setTipo(e.target.value as TipoDePublicacao)}>
          {Object.entries(NOME_DO_TIPO).map(([valor, nome]) => <option key={valor} value={valor}>{nome}</option>)}
        </select>
      </label>
      <label>Título<input name="titulo" required maxLength={150} defaultValue={inicial?.titulo} /></label>
      <label>Texto<textarea name="texto" required maxLength={5000} rows={6} defaultValue={inicial?.texto} /></label>
      {evento && (
        <>
          <label>Começa em<input name="eventoInicio" type="datetime-local" required
                                 defaultValue={paraCampoDeDataHora(inicial?.eventoInicio)} /></label>
          <label>Termina em (opcional)<input name="eventoFim" type="datetime-local"
                                             defaultValue={paraCampoDeDataHora(inicial?.eventoFim)} /></label>
          <label>Local (opcional)<input name="eventoLocal" maxLength={150} defaultValue={inicial?.eventoLocal ?? ''} /></label>
        </>
      )}
      <fieldset className="links-da-publicacao">
        <legend>Links (opcional)</legend>
        {links.map((l, i) => (
          <div key={i} className="link-da-publicacao">
            <label>Endereço {i + 1}
              <input type="url" required placeholder="https://" maxLength={500} value={l.url}
                     onChange={(e) => mudarLink(i, 'url', e.target.value)} />
            </label>
            <label>Nome do link {i + 1} (opcional)
              <input maxLength={100} value={l.rotulo ?? ''} onChange={(e) => mudarLink(i, 'rotulo', e.target.value)} />
            </label>
            <button type="button" className="secundario" onClick={() => setLinks((atuais) => atuais.filter((_, j) => j !== i))}>
              Tirar o link {i + 1}
            </button>
          </div>
        ))}
        {links.length < MAXIMO_DE_LINKS && (
          <button type="button" className="secundario" onClick={() => setLinks((atuais) => [...atuais, { url: '', rotulo: null }])}>
            Adicionar link
          </button>
        )}
      </fieldset>
    </FormularioDeAcao>
  );
}
