import { useState, type FormEvent } from 'react';
import { ErroDaApi, trocarSenha } from './api';

const MINIMO = 12;

/**
 * Troca da própria senha. Com senha temporária, é a única tela liberada (a API só aceita
 * esta rota e /api/me até a troca).
 */
export function TrocarSenha({ obrigatoria, aoTrocar }: { obrigatoria: boolean; aoTrocar: () => void }) {
  const [atual, setAtual] = useState('');
  const [nova, setNova] = useState('');
  const [confirmacao, setConfirmacao] = useState('');
  const [erro, setErro] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);
  const [trocada, setTrocada] = useState(false);

  const problema =
    nova.length > 0 && nova.length < MINIMO ? `A nova senha precisa ter pelo menos ${MINIMO} caracteres.`
      : confirmacao.length > 0 && confirmacao !== nova ? 'A confirmação não é igual à nova senha.'
        : nova.length > 0 && nova === atual ? 'A nova senha precisa ser diferente da atual.'
          : null;

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    setErro(null);
    setEnviando(true);
    try {
      await trocarSenha(atual, nova);
      setTrocada(true);
      aoTrocar();
    } catch (e) {
      setErro(e instanceof ErroDaApi ? mensagemDe(e) : 'Erro inesperado. Tente de novo.');
    } finally {
      setEnviando(false);
    }
  }

  if (trocada && !obrigatoria) {
    return <p className="sucesso" role="status">Senha trocada. As outras sessões abertas com a senha antiga foram encerradas.</p>;
  }

  return (
    <form onSubmit={enviar} className="cartao" noValidate>
      <h1>{obrigatoria ? 'Crie sua senha' : 'Trocar minha senha'}</h1>
      {obrigatoria && <p>Você entrou com uma senha temporária. Para continuar, crie uma senha só sua.</p>}
      <label>
        Senha atual
        <input type="password" autoComplete="current-password" value={atual} onChange={(e) => setAtual(e.target.value)} />
      </label>
      <label>
        Nova senha (mínimo de {MINIMO} caracteres)
        <input type="password" autoComplete="new-password" value={nova} onChange={(e) => setNova(e.target.value)} />
      </label>
      <label>
        Repita a nova senha
        <input type="password" autoComplete="new-password" value={confirmacao}
               onChange={(e) => setConfirmacao(e.target.value)} />
      </label>
      {(problema || erro) && <p className="erro" role="alert">{erro ?? problema}</p>}
      <button type="submit" disabled={enviando || !atual || !nova || confirmacao !== nova || problema !== null}>
        {enviando ? 'Salvando…' : 'Salvar nova senha'}
      </button>
    </form>
  );
}

function mensagemDe(e: ErroDaApi): string {
  // "senha atual incorreta", "a senha precisa ter...": a API já explica; só a primeira letra muda.
  const texto = e.message.charAt(0).toUpperCase() + e.message.slice(1);
  return texto.endsWith('.') ? texto : texto + '.';
}
