import { useEffect, useState } from 'react';
import { useParams } from 'react-router';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { ultimoMinuto, type PagamentoOnline } from '../../componentes/PagamentoGerado';
import { dinheiro } from '../../formato';
import { chamar } from '../../sessao/api';

const SITUACOES: Record<string, string> = {
  AGUARDANDO: 'Aguardando pagamento', PAGO: 'Pago', EXPIRADO: 'Vencido', CANCELADO: 'Cancelado', A_DEVOLVER: 'Pago, a devolver',
  DEVOLVENDO: 'Pago, em devolução', DEVOLVIDO: 'Devolvido',
};

/**
 * O "PDF do boleto" no modo de teste. Com uma empresa de verdade, o link "Abrir o boleto" leva ao boleto dela, numa
 * aba nova; aqui é uma tela do próprio app, que mostra o boleto com o aviso de que não vale. O aluno vê os dele; a
 * recepção e o ADMIN, os que geraram no balcão. Sem o provedor simulado, a API responde 404.
 */
export function BoletoSimulado() {
  const { id } = useParams();
  const [boleto, setBoleto] = useState<PagamentoOnline | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => {
    let atual = true;
    chamar<PagamentoOnline>(`/api/pagamentos-online/${id}/boleto-simulado`)
      .then((b) => { if (atual) setBoleto(b); }).catch((e) => { if (atual) setErro(e); });
    return () => { atual = false; };
  }, [id]);

  return (
    <>
      <h1>Boleto (simulado)</h1>
      <p className="aviso" role="note">
        Modo de teste: este boleto não vale e não deve ser pago. Com uma empresa de verdade, aqui abriria o boleto do banco.
      </p>
      <MensagemDeErro erro={erro} />
      {boleto && (
        <>
          <dl className="dados">
            <dt>Beneficiário</dt><dd>Academia</dd>
            <dt>Valor</dt><dd>{dinheiro(boleto.total)}</dd>
            <dt>Pague até</dt><dd>{ultimoMinuto(boleto.validoAte)}</dd>
            <dt>Situação</dt><dd>{SITUACOES[boleto.situacao] ?? boleto.situacao}</dd>
          </dl>
          <p><strong>Linha digitável</strong></p>
          <p className="linha-digitavel"><code>{boleto.boletoLinhaDigitavel}</code></p>
        </>
      )}
    </>
  );
}
