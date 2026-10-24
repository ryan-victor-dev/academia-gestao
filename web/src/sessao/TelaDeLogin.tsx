import { useEffect, useState, type FormEvent } from 'react';
import { entrar, ErroDaApi, opcoesDoLogin } from './api';
import { EsqueciASenha } from './EsqueciASenha';
import { PrimeiroAcesso } from './PrimeiroAcesso';

export function TelaDeLogin({ aoEntrar }: { aoEntrar: () => void }) {
  const [email, setEmail] = useState('');
  const [senha, setSenha] = useState('');
  const [erro, setErro] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);
  const [opcoes, setOpcoes] = useState({ esqueciSenha: false, primeiroAcesso: false });
  const [outraTela, setOutraTela] = useState<'esqueci' | 'primeiro' | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);

  useEffect(() => {
    let ativo = true;
    opcoesDoLogin().then((o) => { if (ativo) setOpcoes(o); });
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

  if (outraTela !== null) {
    const voltar = (texto: string) => (pronto: boolean) => {
      setOutraTela(null);
      setAviso(pronto ? texto : null);
      setSenha('');
    };
    return (
      <main className="pagina login">
        {outraTela === 'esqueci'
          ? <EsqueciASenha emailInicial={email} aoVoltar={voltar('Senha nova criada. Entre com ela.')} />
          : <PrimeiroAcesso emailInicial={email} aoVoltar={voltar('Acesso criado. Entre com o seu e-mail e a senha nova.')} />}
      </main>
    );
  }

  const abrir = (tela: 'esqueci' | 'primeiro') => () => { setErro(null); setOutraTela(tela); };

  return (
    <main className="pagina login">
      <form onSubmit={enviar} className="cartao" noValidate>
        <h1>Entrar</h1>
        {aviso && <p className="sucesso" role="status">{aviso}</p>}
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
        {opcoes.esqueciSenha && (
          <button type="button" className="secundario" onClick={abrir('esqueci')}>Esqueci a senha</button>
        )}
        {opcoes.primeiroAcesso && (
          <button type="button" className="secundario" onClick={abrir('primeiro')}>Primeiro acesso (aluno)</button>
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
