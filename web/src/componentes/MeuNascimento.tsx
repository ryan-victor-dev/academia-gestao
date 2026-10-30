import { useState, type FormEvent } from 'react';
import { anos, data, hoje } from '../formato';
import { chamar } from '../sessao/api';
import { MensagemDeErro } from './MensagemDeErro';

/**
 * A própria data de nascimento, para quem é da equipe (ADR 0048): quem foi cadastrado antes dela informa aqui, e
 * todos podem corrigir. Só a própria pessoa e a administração veem.
 */
export function MeuNascimento({ inicial }: { inicial: string | null | undefined }) {
  const [atual, setAtual] = useState(inicial ?? null);
  const [salvo, setSalvo] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const [erro, setErro] = useState<unknown>(null);

  async function salvar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    const f = new FormData(evento.currentTarget);
    setErro(null);
    setSalvo(false);
    setEnviando(true);
    try {
      const resposta = await chamar<{ dataNascimento: string }>('/api/me/nascimento',
        { metodo: 'PUT', corpo: { dataNascimento: f.get('dataNascimento') || null } });
      setAtual(resposta.dataNascimento);
      setSalvo(true);
    } catch (e) {
      setErro(e);
    } finally {
      setEnviando(false);
    }
  }

  return (
    <section>
      <h2>Data de nascimento</h2>
      <p className="suave">Só você e a administração da academia veem.</p>
      {atual
        ? <p>{data(atual)} · {anos(atual)}</p>
        : <p className="etiqueta alerta">Ainda não informada</p>}
      <MensagemDeErro erro={erro} rotulos={{ dataNascimento: 'Data de nascimento' }} />
      <form className="formulario" onSubmit={salvar} aria-label="Minha data de nascimento">
        <label>Data de nascimento
          <input name="dataNascimento" type="date" required max={hoje()} defaultValue={atual ?? ''} />
        </label>
        <div className="acoes"><button type="submit" disabled={enviando}>{enviando ? 'Salvando…' : 'Salvar'}</button></div>
        {salvo && <p role="status">Salvo.</p>}
      </form>
    </section>
  );
}
