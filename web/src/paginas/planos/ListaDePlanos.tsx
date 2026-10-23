import { useEffect, useState } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { dinheiro } from '../../formato';
import { chamar } from '../../sessao/api';

export interface Plano {
  id: string;
  nome: string;
  valorMensal: number;
  duracaoMeses: number;
  valorTotal: number;
  fidelidade: boolean;
  percentualMulta: number | null;
  formaPagamento: 'MENSAL' | 'A_VISTA';
  ativo?: boolean;
}

export const meses = (n: number) => (n === 1 ? '1 mês' : `${n} meses`);

/** Os planos ativos (o aluno vê só esses; a equipe também). */
export function ListaDePlanos() {
  const [planos, setPlanos] = useState<Plano[] | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => { chamar<Plano[]>('/api/planos').then(setPlanos).catch(setErro); }, []);

  return (
    <>
      <h1>Planos</h1>
      <MensagemDeErro erro={erro} />
      {planos?.length === 0 && <p>Nenhum plano disponível.</p>}
      <ul className="cartoes planos">
        {planos?.map((p) => (
          <li key={p.id} className="cartao-item">
            <strong>{p.nome}</strong>
            <p className="valor">{dinheiro(p.valorMensal)}<span className="suave">/mês</span></p>
            <p>{meses(p.duracaoMeses)} · {p.formaPagamento === 'A_VISTA' ? `à vista: ${dinheiro(p.valorTotal)}` : 'pagamento mensal'}</p>
            {p.fidelidade && <p className="suave">Com fidelidade: sair antes do fim tem multa de {p.percentualMulta}% do saldo restante.</p>}
          </li>
        ))}
      </ul>
    </>
  );
}
