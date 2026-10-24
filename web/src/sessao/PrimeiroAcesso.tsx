import { useState, type FormEvent } from 'react';
import { criarSenhaDoPrimeiroAcesso, ErroDaApi, pedirCodigoDoPrimeiroAcesso } from './api';

const MINIMO = 12;

/**
 * Primeiro acesso do aluno (ADR 0039): com o CPF e o e-mail do cadastro, o código de 6 dígitos
 * vai para esse e-mail, e com ele o aluno cria a própria senha. A mensagem não diz se os dados
 * são de um aluno (a API também não).
 */
export function PrimeiroAcesso({ emailInicial, aoVoltar }: { emailInicial: string; aoVoltar: (pronto: boolean) => void }) {
  const [cpf, setCpf] = useState('');
  const [email, setEmail] = useState(emailInicial);
  const [pedido, setPedido] = useState(false);
  const [codigo, setCodigo] = useState('');
  const [senha, setSenha] = useState('');
  const [confirmacao, setConfirmacao] = useState('');
  const [erro, setErro] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);

  // Aceita "000.000.000-00" ou só os números; vai só com os números.
  const digitos = cpf.replace(/\D/g, '');
  const problema =
    senha.length > 0 && senha.length < MINIMO ? `A senha precisa ter pelo menos ${MINIMO} caracteres.`
      : confirmacao.length > 0 && confirmacao !== senha ? 'A confirmação não é igual à senha.'
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
      await pedirCodigoDoPrimeiroAcesso(digitos, email);
      setPedido(true);
    });
  };

  const criar = (evento: FormEvent) => {
    evento.preventDefault();
    return executar(async () => {
      await criarSenhaDoPrimeiroAcesso(digitos, email, codigo, senha);
      aoVoltar(true);
    });
  };

  if (!pedido) {
    return (
      <form onSubmit={pedir} className="cartao" noValidate>
        <h1>Primeiro acesso</h1>
        <p>É aluno e ainda não tem login? Digite o CPF e o e-mail do seu cadastro na academia: chega um código
          nesse e-mail para você criar a sua senha.</p>
        <label>
          CPF
          <input inputMode="numeric" autoComplete="off" maxLength={14} value={cpf}
                 onChange={(e) => setCpf(e.target.value.replace(/[^\d.\-]/g, ''))} />
        </label>
        <label>
          E-mail
          <input type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        {erro && <p className="erro" role="alert">{erro}</p>}
        <button type="submit" disabled={enviando || digitos.length !== 11 || !email.includes('@')}>
          {enviando ? 'Enviando…' : 'Enviar código'}
        </button>
        <button type="button" className="secundario" onClick={() => aoVoltar(false)}>Voltar para o login</button>
      </form>
    );
  }

  return (
    <form onSubmit={criar} className="cartao" noValidate>
      <h1>Criar a sua senha</h1>
      <p>Se o CPF e o e-mail <strong>{email}</strong> forem de um aluno ativo ainda sem login, o código já está
        chegando (confira também a caixa de spam). Ele vale 15 minutos. Não chegou? Procure a recepção: o e-mail do
        cadastro pode estar diferente, ou você já tem login.</p>
      <label>
        Código
        <input inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={codigo}
               onChange={(e) => setCodigo(e.target.value.replace(/\D/g, ''))} />
      </label>
      <label>
        Senha (mínimo de {MINIMO} caracteres)
        <input type="password" autoComplete="new-password" value={senha} onChange={(e) => setSenha(e.target.value)} />
      </label>
      <label>
        Repita a senha
        <input type="password" autoComplete="new-password" value={confirmacao} onChange={(e) => setConfirmacao(e.target.value)} />
      </label>
      {(problema || erro) && <p className="erro" role="alert">{erro ?? problema}</p>}
      <button type="submit" disabled={enviando || codigo.length !== 6 || !senha || confirmacao !== senha || problema !== null}>
        {enviando ? 'Salvando…' : 'Criar o acesso'}
      </button>
      <button type="button" className="secundario" onClick={() => aoVoltar(false)}>Voltar para o login</button>
    </form>
  );
}
