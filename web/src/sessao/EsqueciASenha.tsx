import { useState, type FormEvent } from 'react';
import { ErroDaApi, pedirCodigoDeSenha, redefinirSenha } from './api';

const MINIMO = 12;

/**
 * "Esqueci a senha" (ADR 0038): o código de 6 dígitos vai para o e-mail, e com ele se cria uma
 * senha nova. A mensagem não diz se o e-mail está cadastrado (a API também não).
 */
export function EsqueciASenha({ emailInicial, aoVoltar }: { emailInicial: string; aoVoltar: (pronta: boolean) => void }) {
  const [email, setEmail] = useState(emailInicial);
  const [pedido, setPedido] = useState(false);
  const [codigo, setCodigo] = useState('');
  const [nova, setNova] = useState('');
  const [confirmacao, setConfirmacao] = useState('');
  const [erro, setErro] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);

  const problema =
    nova.length > 0 && nova.length < MINIMO ? `A nova senha precisa ter pelo menos ${MINIMO} caracteres.`
      : confirmacao.length > 0 && confirmacao !== nova ? 'A confirmação não é igual à nova senha.'
        : null;

  async function executar(acao: () => Promise<void>) {
    setErro(null);
    setEnviando(true);
    try {
      await acao();
    } catch (e) {
      setErro(e instanceof ErroDaApi ? e.message : 'Erro inesperado. Tente de novo.');
    } finally {
      setEnviando(false);
    }
  }

  const pedir = (evento: FormEvent) => {
    evento.preventDefault();
    return executar(async () => {
      await pedirCodigoDeSenha(email);
      setPedido(true);
    });
  };

  const redefinir = (evento: FormEvent) => {
    evento.preventDefault();
    return executar(async () => {
      await redefinirSenha(email, codigo, nova);
      aoVoltar(true);
    });
  };

  if (!pedido) {
    return (
      <form onSubmit={pedir} className="cartao" noValidate>
        <h1>Esqueci a senha</h1>
        <p>Digite o e-mail do seu login. Se ele estiver cadastrado e confirmado, chega um código para criar uma senha nova.</p>
        <label>
          E-mail
          <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        {erro && <p className="erro" role="alert">{erro}</p>}
        <button type="submit" disabled={enviando || !email.includes('@')}>{enviando ? 'Enviando…' : 'Enviar código'}</button>
        <button type="button" className="secundario" onClick={() => aoVoltar(false)}>Voltar para o login</button>
      </form>
    );
  }

  return (
    <form onSubmit={redefinir} className="cartao" noValidate>
      <h1>Criar uma senha nova</h1>
      <p>Se <strong>{email}</strong> estiver cadastrado e confirmado, o código já está chegando (confira também a caixa de spam).
        Ele vale 15 minutos.</p>
      <label>
        Código
        <input inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={codigo}
               onChange={(e) => setCodigo(e.target.value.replace(/\D/g, ''))} />
      </label>
      <label>
        Nova senha (mínimo de {MINIMO} caracteres)
        <input type="password" autoComplete="new-password" value={nova} onChange={(e) => setNova(e.target.value)} />
      </label>
      <label>
        Repita a nova senha
        <input type="password" autoComplete="new-password" value={confirmacao} onChange={(e) => setConfirmacao(e.target.value)} />
      </label>
      {(problema || erro) && <p className="erro" role="alert">{erro ?? problema}</p>}
      <button type="submit" disabled={enviando || codigo.length !== 6 || !nova || confirmacao !== nova || problema !== null}>
        {enviando ? 'Salvando…' : 'Salvar nova senha'}
      </button>
      <button type="button" className="secundario" onClick={() => aoVoltar(false)}>Voltar para o login</button>
    </form>
  );
}
