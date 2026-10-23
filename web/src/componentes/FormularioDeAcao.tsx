import { useState, type FormEvent, type ReactNode } from 'react';

/** Formulário curto de uma ação; os campos vêm pelo nome (FormData). */
export function FormularioDeAcao({ titulo, rotulo = 'Salvar', children, aoEnviar, aoCancelar }: {
  titulo: string; rotulo?: string; children: ReactNode;
  aoEnviar: (f: FormData) => Promise<void>; aoCancelar?: () => void;
}) {
  const [enviando, setEnviando] = useState(false);
  async function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    setEnviando(true);
    try {
      await aoEnviar(new FormData(evento.currentTarget));
    } finally {
      setEnviando(false);
    }
  }
  return (
    <form className="formulario acao" onSubmit={enviar} aria-label={titulo}>
      <h3>{titulo}</h3>
      {children}
      <div className="acoes">
        <button type="submit" disabled={enviando}>{enviando ? 'Enviando…' : rotulo}</button>
        {aoCancelar && <button type="button" className="secundario" onClick={aoCancelar}>Voltar</button>}
      </div>
    </form>
  );
}
