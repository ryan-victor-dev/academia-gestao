import { useEffect, useState, type FormEvent } from 'react';
import { chamar } from '../sessao/api';
import { MensagemDeErro } from './MensagemDeErro';

/** As opções da API (ADR 0047): as identidades e orientações usadas hoje, "outra" e "prefiro não informar". */
export const IDENTIDADES_DE_GENERO: Record<string, string> = {
  MULHER_CIS: 'Mulher cisgênero', HOMEM_CIS: 'Homem cisgênero', MULHER_TRANS: 'Mulher trans', HOMEM_TRANS: 'Homem trans',
  TRAVESTI: 'Travesti', NAO_BINARIA: 'Pessoa não binária', GENERO_FLUIDO: 'Gênero fluido', AGENERO: 'Agênero',
  OUTRA: 'Outra (descrever)', PREFIRO_NAO_INFORMAR: 'Prefiro não informar',
};
export const ORIENTACOES_SEXUAIS: Record<string, string> = {
  HETEROSSEXUAL: 'Heterossexual', LESBICA: 'Lésbica', GAY: 'Gay', BISSEXUAL: 'Bissexual', PANSEXUAL: 'Pansexual',
  ASSEXUAL: 'Assexual', DEMISSEXUAL: 'Demissexual', QUEER: 'Queer',
  OUTRA: 'Outra (descrever)', PREFIRO_NAO_INFORMAR: 'Prefiro não informar',
};

interface DadosDeIdentidade {
  identidadeDeGenero: string | null;
  identidadeDeGeneroOutra: string | null;
  orientacaoSexual: string | null;
  orientacaoSexualOutra: string | null;
}

/**
 * Identidade de gênero e orientação sexual (ADR 0047): opcionais, dado sensível pela LGPD. Só a própria
 * pessoa e o ADMIN chegam aqui (a API responde 403 aos outros). `caminho` é a rota da API: a própria
 * (`/api/me/identidade`, `/api/alunos/me/identidade`) ou a de alguém, para o ADMIN.
 */
export function Identidade({ caminho, titulo, somenteLeitura = false }: { caminho: string; titulo: string; somenteLeitura?: boolean }) {
  const [dados, setDados] = useState<DadosDeIdentidade | null>(null);
  const [genero, setGenero] = useState('');
  const [orientacao, setOrientacao] = useState('');
  const [salvo, setSalvo] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => {
    let atual = true;
    chamar<DadosDeIdentidade>(caminho).then((d) => {
      if (!atual) return;
      setDados(d);
      setGenero(d.identidadeDeGenero ?? '');
      setOrientacao(d.orientacaoSexual ?? '');
    }).catch((e) => { if (atual) setErro(e); });
    return () => { atual = false; };
  }, [caminho]);

  async function salvar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    const f = new FormData(evento.currentTarget);
    setErro(null);
    setSalvo(false);
    setEnviando(true);
    try {
      setDados(await chamar<DadosDeIdentidade>(caminho, { metodo: 'PUT', corpo: {
        identidadeDeGenero: genero || null,
        identidadeDeGeneroOutra: genero === 'OUTRA' ? f.get('identidadeDeGeneroOutra') : null,
        orientacaoSexual: orientacao || null,
        orientacaoSexualOutra: orientacao === 'OUTRA' ? f.get('orientacaoSexualOutra') : null,
      } }));
      setSalvo(true);
    } catch (e) {
      setErro(e);
    } finally {
      setEnviando(false);
    }
  }

  return (
    <section>
      <h2>{titulo}</h2>
      <p className="suave">
        Opcional. Só a própria pessoa e a administração da academia veem; a recepção e os professores, não.
        Pode mudar ou apagar quando quiser (deixe "Não respondido").
      </p>
      <MensagemDeErro erro={erro} rotulos={{ identidadeDeGeneroOutra: 'Identidade de gênero', orientacaoSexualOutra: 'Orientação sexual' }} />
      {dados && (
        <form className="formulario" onSubmit={salvar} aria-label={titulo}>
          <label>Identidade de gênero
            <select aria-label="Identidade de gênero" value={genero} disabled={somenteLeitura}
                    onChange={(e) => { setGenero(e.target.value); setSalvo(false); }}>
              <option value="">Não respondido</option>
              {Object.entries(IDENTIDADES_DE_GENERO).map(([valor, nome]) => <option key={valor} value={valor}>{nome}</option>)}
            </select>
          </label>
          {genero === 'OUTRA' && (
            <label>Descreva a identidade de gênero
              <input name="identidadeDeGeneroOutra" required maxLength={50} disabled={somenteLeitura}
                     defaultValue={dados.identidadeDeGeneroOutra ?? ''} />
            </label>
          )}
          <label>Orientação sexual
            <select aria-label="Orientação sexual" value={orientacao} disabled={somenteLeitura}
                    onChange={(e) => { setOrientacao(e.target.value); setSalvo(false); }}>
              <option value="">Não respondido</option>
              {Object.entries(ORIENTACOES_SEXUAIS).map(([valor, nome]) => <option key={valor} value={valor}>{nome}</option>)}
            </select>
          </label>
          {orientacao === 'OUTRA' && (
            <label>Descreva a orientação sexual
              <input name="orientacaoSexualOutra" required maxLength={50} disabled={somenteLeitura}
                     defaultValue={dados.orientacaoSexualOutra ?? ''} />
            </label>
          )}
          <p className="suave">Cisgênero: quem se identifica com o gênero atribuído no nascimento.</p>
          {somenteLeitura
            ? <p className="suave">Com a matrícula inativa, só dá para consultar: para mudar, fale com a administração.</p>
            : <div className="acoes"><button type="submit" disabled={enviando}>{enviando ? 'Salvando…' : 'Salvar'}</button></div>}
          {salvo && <p role="status">Salvo.</p>}
        </form>
      )}
    </section>
  );
}
