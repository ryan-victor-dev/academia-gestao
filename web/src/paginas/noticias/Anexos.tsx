import { useState, type ChangeEvent } from 'react';
import { Excluir } from '../../componentes/Excluir';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { chamar } from '../../sessao/api';
import { MAXIMO_DE_ANEXOS, TIPOS_ACEITOS, type AnexoDaPublicacao } from './tipos';

/** "2,5 MB", "300 KB". */
export function tamanhoLegivel(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

/**
 * Os anexos de uma publicação (ADR 0047): a foto e o vídeo aparecem na página; o documento, como link de download.
 * O endereço já vem assinado da API (o <img> e o <video> não mandam o token).
 */
export function AnexosDaPublicacao({ anexos }: { anexos: AnexoDaPublicacao[] }) {
  if (anexos.length === 0) return null;
  return (
    <div className="anexos">
      {anexos.map((a) => {
        if (a.tipo === 'IMAGEM') return <img key={a.id} src={a.endereco} alt={a.nome} loading="lazy" className="anexo-imagem" />;
        if (a.tipo === 'VIDEO') {
          return <video key={a.id} src={a.endereco} controls preload="metadata" className="anexo-video" aria-label={a.nome} />;
        }
        return <a key={a.id} href={a.endereco} download={a.nome} className="anexo-documento">{a.nome} ({tamanhoLegivel(a.tamanho)})</a>;
      })}
    </div>
  );
}

/** Envia os arquivos um de cada vez (o servidor aceita poucos ao mesmo tempo); para no primeiro que falhar. */
export async function enviarArquivos(publicacaoId: string, arquivos: File[], aoAvancar: (n: number) => void): Promise<void> {
  for (let i = 0; i < arquivos.length; i++) {
    aoAvancar(i + 1);
    await chamar(`/api/publicacoes/${publicacaoId}/anexos`, { metodo: 'POST', arquivo: arquivos[i] });
  }
}

/** Para o ADMIN e a recepção: tirar um anexo ou mandar mais (até 10 por publicação). */
export function GerenciarAnexos({ publicacaoId, anexos, aoMudar }: {
  publicacaoId: string; anexos: AnexoDaPublicacao[]; aoMudar: () => void;
}) {
  const [enviando, setEnviando] = useState<{ atual: number; total: number } | null>(null);
  const [erro, setErro] = useState<unknown>(null);
  const cabem = MAXIMO_DE_ANEXOS - anexos.length;

  async function escolher(evento: ChangeEvent<HTMLInputElement>) {
    const arquivos = Array.from(evento.target.files ?? []).slice(0, cabem);
    evento.target.value = '';
    if (arquivos.length === 0) return;
    setErro(null);
    try {
      await enviarArquivos(publicacaoId, arquivos, (atual) => setEnviando({ atual, total: arquivos.length }));
    } catch (e) {
      setErro(e);
    } finally {
      setEnviando(null);
      aoMudar();
    }
  }

  return (
    <div className="gerenciar-anexos">
      <MensagemDeErro erro={erro} />
      {anexos.length > 0 && (
        <ul className="lista-de-anexos">
          {anexos.map((a) => (
            <li key={a.id}>
              <span>{a.nome} ({tamanhoLegivel(a.tamanho)})</span>
              <Excluir nome={a.nome} aoExcluir={async () => {
                setErro(null);
                try {
                  await chamar(`/api/publicacoes/${publicacaoId}/anexos/${a.id}`, { metodo: 'DELETE' });
                  aoMudar();
                } catch (e) {
                  setErro(e);
                }
              }} />
            </li>
          ))}
        </ul>
      )}
      {enviando
        ? <p role="status">Enviando {enviando.atual} de {enviando.total}…</p>
        : cabem > 0 && (
          <label className="escolher-arquivos">Adicionar fotos, vídeos ou documentos
            <input type="file" multiple accept={TIPOS_ACEITOS} onChange={escolher} />
          </label>
        )}
      <p className="suave">Fotos até 5 MB, PDF até 10 MB e vídeos curtos até 50 MB (vídeo longo: mande o link). Até {MAXIMO_DE_ANEXOS} por publicação.</p>
    </div>
  );
}
