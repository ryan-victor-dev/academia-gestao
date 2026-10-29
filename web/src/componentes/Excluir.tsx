import { useState } from 'react';

/**
 * Excluir de vez (só o ADMIN, ADR 0043), com confirmação na própria linha. Só sai o cadastro que
 * nunca foi usado: com histórico, a API recusa (409) e quem chama mostra a mensagem, que manda
 * inativar. {@code aoExcluir} trata o próprio erro.
 */
export function Excluir({ nome, aoExcluir }: { nome: string; aoExcluir: () => Promise<void> }) {
  const [confirmando, setConfirmando] = useState(false);
  const [enviando, setEnviando] = useState(false);

  if (!confirmando) {
    return <button type="button" className="perigo" onClick={() => setConfirmando(true)}>Excluir</button>;
  }
  async function confirmar() {
    setEnviando(true);
    try {
      await aoExcluir();
    } finally {
      setEnviando(false);
      setConfirmando(false);
    }
  }
  return (
    <span className="confirmar" role="group" aria-label={`Excluir ${nome}`}>
      <span>Excluir {nome} de vez?</span>
      <button type="button" className="perigo" disabled={enviando} onClick={confirmar}>
        {enviando ? 'Excluindo…' : 'Sim, excluir'}
      </button>
      <button type="button" className="secundario" disabled={enviando} onClick={() => setConfirmando(false)}>Voltar</button>
    </span>
  );
}
