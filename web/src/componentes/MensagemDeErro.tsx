import { ErroDaApi } from '../sessao/api';

/** Erro de uma ação: a explicação da API e, se houver, o problema de cada campo. */
export function MensagemDeErro({ erro, rotulos = {} }: { erro: unknown; rotulos?: Record<string, string> }) {
  if (!erro) return null;
  const mensagem = erro instanceof ErroDaApi ? erro.message : 'Erro inesperado. Tente de novo.';
  const campos = erro instanceof ErroDaApi ? Object.entries(erro.erros) : [];
  return (
    <div className="erro" role="alert">
      <p>{mensagem}</p>
      {campos.length > 0 && (
        <ul>
          {campos.map(([campo, problema]) => (
            <li key={campo}>{rotulos[campo] ?? campo}: {problema}</li>
          ))}
        </ul>
      )}
    </div>
  );
}
