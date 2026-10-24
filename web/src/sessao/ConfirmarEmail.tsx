import { useState, type FormEvent } from 'react';
import { MensagemDeErro } from '../componentes/MensagemDeErro';
import { confirmarEmail, corrigirEmail, enviarCodigoDeEmail } from './api';

/**
 * A confirmação do e-mail no primeiro acesso (ADR 0038): um código de 6 dígitos chega no e-mail
 * e é digitado aqui. Até confirmar, é a única tela liberada. O e-mail errado se corrige aqui.
 */
export function ConfirmarEmail({ email, aoConfirmar }: { email: string; aoConfirmar: () => void }) {
  const [enviadoPara, setEnviadoPara] = useState<string | null>(null);
  const [codigo, setCodigo] = useState('');
  const [corrigindo, setCorrigindo] = useState(false);
  const [novoEmail, setNovoEmail] = useState('');
  const [erro, setErro] = useState<unknown>(null);
  const [ocupado, setOcupado] = useState(false);

  async function executar(acao: () => Promise<void>) {
    setErro(null);
    setOcupado(true);
    try {
      await acao();
    } catch (e) {
      setErro(e);
    } finally {
      setOcupado(false);
    }
  }

  const enviar = () => executar(async () => {
    await enviarCodigoDeEmail();
    setEnviadoPara(enviadoPara ?? email);
  });

  const corrigir = (evento: FormEvent) => {
    evento.preventDefault();
    return executar(async () => {
      await corrigirEmail(novoEmail);
      setEnviadoPara(novoEmail.trim().toLowerCase());
      setCorrigindo(false);
      setCodigo('');
    });
  };

  const confirmar = (evento: FormEvent) => {
    evento.preventDefault();
    return executar(async () => {
      await confirmarEmail(codigo);
      aoConfirmar();
    });
  };

  return (
    <div className="cartao">
      <h1>Confirme seu e-mail</h1>
      <p>Para continuar, confirme que este e-mail é seu: <strong>{enviadoPara ?? email}</strong></p>
      <MensagemDeErro erro={erro} />
      {enviadoPara === null ? (
        <button type="button" disabled={ocupado} onClick={enviar}>{ocupado ? 'Enviando…' : 'Enviar código'}</button>
      ) : (
        <form onSubmit={confirmar} noValidate>
          <p className="suave">Enviamos um código de 6 dígitos. Ele vale 15 minutos; confira também a caixa de spam.</p>
          <label>
            Código
            <input inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={codigo}
                   onChange={(e) => setCodigo(e.target.value.replace(/\D/g, ''))} />
          </label>
          <div className="acoes">
            <button type="submit" disabled={ocupado || codigo.length !== 6}>{ocupado ? 'Confirmando…' : 'Confirmar'}</button>
            <button type="button" className="secundario" disabled={ocupado} onClick={enviar}>Enviar outro código</button>
          </div>
        </form>
      )}
      {corrigindo ? (
        <form onSubmit={corrigir} noValidate>
          <label>
            E-mail certo
            <input type="email" autoComplete="email" value={novoEmail} onChange={(e) => setNovoEmail(e.target.value)} />
          </label>
          <div className="acoes">
            <button type="submit" disabled={ocupado || !novoEmail.includes('@')}>Corrigir e enviar código</button>
            <button type="button" className="secundario" disabled={ocupado} onClick={() => setCorrigindo(false)}>Voltar</button>
          </div>
        </form>
      ) : (
        <button type="button" className="secundario" disabled={ocupado} onClick={() => setCorrigindo(true)}>O e-mail está errado?</button>
      )}
    </div>
  );
}
