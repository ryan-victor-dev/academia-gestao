import { useEffect, useState, type FormEvent } from 'react';
import { entrar, ErroDaApi, opcoesDoLogin } from './api';
import { EsqueciASenha } from './EsqueciASenha';

export function TelaDeLogin({ aoEntrar }: { aoEntrar: () => void }) {
  const [email, setEmail] = useState('');
  const [senha, setSenha] = useState('');
  const [erro, setErro] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);
  const [esqueciSenha, setEsqueciSenha] = useState(false);
  const [recuperando, setRecuperando] = useState(false);
  const [senhaCriada, setSenhaCriada] = useState(false);

  useEffect(() => {
    let ativo = true;
    opcoesDoLogin().then((o) => { if (ativo) setEsqueciSenha(o.esqueciSenha); });
    return () => { ativo = false; };
  }, []);

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    setErro(null);
    setEnviando(true);
    try {
      // O campo type="email" já chega sem espaços nas pontas (o navegador os tira).
      await entrar(email, senha);
      aoEntrar();
    } catch (e) {
      setErro(mensagemDe(e));
      setEnviando(false);
    }
  }

  if (recuperando) {
    return (
      <main className="pagina login">
        <EsqueciASenha emailInicial={email} aoVoltar={(pronta) => {
          setRecuperando(false);
          setSenhaCriada(pronta);
          setSenha('');
        }} />
      </main>
    );
  }

  return (
    <main className="pagina login">
      <form onSubmit={enviar} className="cartao" noValidate>
        <h1>Entrar</h1>
        {senhaCriada && <p className="sucesso" role="status">Senha nova criada. Entre com ela.</p>}
        <label>
          E-mail
          <input type="email" autoComplete="username" required value={email}
                 onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label>
          Senha
          <input type="password" autoComplete="current-password" required value={senha}
                 onChange={(e) => setSenha(e.target.value)} />
        </label>
        {erro && <p className="erro" role="alert">{erro}</p>}
        <button type="submit" disabled={enviando || !email || !senha}>
          {enviando ? 'Entrando…' : 'Entrar'}
        </button>
        {esqueciSenha && (
          <button type="button" className="secundario" onClick={() => { setErro(null); setRecuperando(true); }}>Esqueci a senha</button>
        )}
      </form>
    </main>
  );
}

function mensagemDe(e: unknown): string {
  if (!(e instanceof ErroDaApi)) return 'Erro inesperado. Tente de novo.';
  switch (e.status) {
    case 400:
      // A API diria "dados inválidos" com a lista de campos; aqui basta apontar onde olhar.
      return 'Confira o e-mail e a senha.';
    default:
      // 401, 429 (muitas tentativas) e 503 já trazem a explicação da API.
      return e.message;
  }
}
