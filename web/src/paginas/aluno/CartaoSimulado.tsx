import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { chamar } from '../../sessao/api';

/**
 * A "página da empresa" do cartão no modo de teste (ADR 0037). Com uma empresa de verdade, o aluno
 * vai à página dela, digita o cartão lá e volta. Aqui ela é uma tela do próprio app, que não pede
 * cartão nenhum: aprovar faz o mesmo que o "simular pagamento" do Pix.
 */
export function CartaoSimulado() {
  const { id } = useParams();
  const navegar = useNavigate();
  const [erro, setErro] = useState<unknown>(null);
  const [enviando, setEnviando] = useState(false);

  async function aprovar() {
    setErro(null);
    setEnviando(true);
    try {
      await chamar<void>(`/api/pagamentos-online/me/${id}/simular-pagamento`, { metodo: 'POST' });
      navegar('/minhas-cobrancas');
    } catch (e) {
      setErro(e);
      setEnviando(false);
    }
  }

  return (
    <>
      <h1>Pagamento com cartão (simulado)</h1>
      <p className="aviso" role="note">
        Modo de teste: esta página faz o papel da empresa de pagamentos. Nenhum cartão é pedido nem cobrado.
      </p>
      <p className="suave">Com uma empresa de verdade, aqui você digitaria o cartão, na página segura dela.</p>
      <MensagemDeErro erro={erro} />
      <div className="acoes">
        <button type="button" disabled={enviando} onClick={aprovar}>{enviando ? 'Aprovando…' : 'Aprovar pagamento (teste)'}</button>
        <button type="button" className="secundario" disabled={enviando} onClick={() => navegar('/minhas-cobrancas')}>Voltar sem pagar</button>
      </div>
    </>
  );
}
