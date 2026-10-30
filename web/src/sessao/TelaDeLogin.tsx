import { useEffect, useState, type FormEvent } from 'react';
import { entrar, ErroDaApi, opcoesDoLogin, type OpcoesDoLogin } from './api';
import { AvisoDeEspera, useEspera } from './Espera';
import { EsqueciASenha } from './EsqueciASenha';
import { PrimeiroAcesso } from './PrimeiroAcesso';

/** Esperas (ms) antes de perguntar de novo pelas opções: dá tempo de o servidor terminar de subir. */
export const ESPERAS_DAS_OPCOES = [2000, 5000];

export function TelaDeLogin({ aoEntrar }: { aoEntrar: () => void }) {
  const [email, setEmail] = useState('');
  const [senha, setSenha] = useState('');
  const [erro, setErro] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);
  // null: ainda perguntando; 'falhou': nem depois das novas tentativas (o aviso diz, e não esconde calado).
  const [opcoes, setOpcoes] = useState<OpcoesDoLogin | 'falhou' | null>(null);
  const [perguntas, setPerguntas] = useState(0);
  const [outraTela, setOutraTela] = useState<'esqueci' | 'primeiro' | null>(null);
  const [aviso, setAviso] = useState<string | null>(null);
  const espera = useEspera();
  // O limite é por conta: a espera vale para o e-mail que a recebeu.
  const conta = email.trim().toLowerCase();
  const { restante } = espera.de(conta);

  useEffect(() => {
    let ativo = true;
    let espera: ReturnType<typeof setTimeout> | undefined;
    const perguntar = (tentativa: number) => {
      opcoesDoLogin().then((o) => {
        if (!ativo) return;
        if (o !== null) {
          setOpcoes(o);
        } else if (tentativa < ESPERAS_DAS_OPCOES.length) {
          espera = setTimeout(() => perguntar(tentativa + 1), ESPERAS_DAS_OPCOES[tentativa]);
        } else {
          setOpcoes('falhou');
        }
      });
    };
    perguntar(0);
    return () => { ativo = false; clearTimeout(espera); };
  }, [perguntas]);

  const tentarDeNovo = () => { setOpcoes(null); setPerguntas((n) => n + 1); };
  const oferece = opcoes !== null && opcoes !== 'falhou' ? opcoes : { esqueciSenha: false, primeiroAcesso: false };

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    setErro(null);
    setEnviando(true);
    try {
      // O campo type="email" já chega sem espaços nas pontas (o navegador os tira).
      await entrar(email, senha);
      aoEntrar();
    } catch (e) {
      if (!espera.aguardar(e, conta)) setErro(mensagemDe(e));
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
        <AvisoDeEspera {...espera.de(conta)} />
        {erro && <p className="erro" role="alert">{erro}</p>}
        <button type="submit" disabled={enviando || !email || !senha || restante > 0}>
          {enviando ? 'Entrando…' : 'Entrar'}
        </button>
        {oferece.esqueciSenha && (
          <button type="button" className="secundario" onClick={abrir('esqueci')}>Esqueci a senha</button>
        )}
        {oferece.primeiroAcesso && (
          <button type="button" className="secundario" onClick={abrir('primeiro')}>Primeiro acesso (aluno)</button>
        )}
        {opcoes === 'falhou' && (
          <p className="suave">
            Não foi possível conferir o "esqueci a senha" e o primeiro acesso.{' '}
            <button type="button" className="secundario" onClick={tentarDeNovo}>Tentar de novo</button>
          </p>
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
