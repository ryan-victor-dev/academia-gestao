import { useCallback, useEffect, useState } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { dinheiro } from '../../formato';
import { chamar, chamarComVersao, ErroDaApi } from '../../sessao/api';
import { useUsuario } from '../../sessao/contexto';
import { FormularioDoPlano } from './FormularioDoPlano';

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

/**
 * Os planos. O aluno vê só os ativos (a API manda só esses a ele); o ADMIN também cria, edita
 * (com a versão lida), inativa e reativa. Mudar um plano não muda as matrículas já feitas (o
 * preço fica congelado na matrícula).
 */
export function ListaDePlanos() {
  const admin = useUsuario().perfil === 'ADMIN';
  const [planos, setPlanos] = useState<Plano[] | null>(null);
  const [inativos, setInativos] = useState(false);
  const [editando, setEditando] = useState<{ plano: Plano; versao: string | null } | 'novo' | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  const carregar = useCallback(async () => {
    try {
      setPlanos(await chamar<Plano[]>(`/api/planos${inativos ? '?incluirInativos=true' : ''}`));
    } catch (e) {
      setErro(e);
    }
  }, [inativos]);
  useEffect(() => { void carregar(); }, [carregar]);

  async function abrirEdicao(id: string) {
    setErro(null);
    try {
      const { dados, versao } = await chamarComVersao<Plano>(`/api/planos/${id}`);
      setEditando({ plano: dados, versao });
    } catch (e) {
      setErro(e);
    }
  }

  async function mudarSituacao(p: Plano) {
    setErro(null);
    try {
      await chamar(`/api/planos/${p.id}/${p.ativo ? 'inativar' : 'reativar'}`, { metodo: 'POST' });
      await carregar();
    } catch (e) {
      setErro(e);
    }
  }

  return (
    <>
      <div className="cabecalho">
        <h1>Planos</h1>
        {admin && !editando && <button type="button" onClick={() => setEditando('novo')}>Novo plano</button>}
      </div>
      {editando && (
        <FormularioDoPlano
          inicial={editando === 'novo' ? undefined : editando.plano}
          aoCancelar={() => setEditando(null)}
          aoSalvar={async (dados) => {
            if (editando === 'novo') {
              await chamar('/api/planos', { metodo: 'POST', corpo: dados });
            } else {
              try {
                await chamar(`/api/planos/${editando.plano.id}`, { metodo: 'PUT', corpo: dados, versao: editando.versao ?? undefined });
              } catch (e) {
                if (e instanceof ErroDaApi && e.status === 412) {
                  throw new ErroDaApi(412, 'Outra pessoa alterou este plano. Cancele e abra de novo.');
                }
                throw e;
              }
            }
            setEditando(null);
            await carregar();
          }} />
      )}
      {admin && (
        <label className="marcar">
          <input type="checkbox" checked={inativos} onChange={(e) => setInativos(e.target.checked)} />
          Mostrar inativos
        </label>
      )}
      <MensagemDeErro erro={erro} />
      {planos?.length === 0 && <p>Nenhum plano disponível.</p>}
      <ul className="cartoes planos">
        {planos?.map((p) => (
          <li key={p.id} className="cartao-item">
            <div className="cabecalho">
              <strong>{p.nome}</strong>
              {p.ativo === false && <span className="etiqueta">Inativo</span>}
            </div>
            <p className="valor">{dinheiro(p.valorMensal)}<span className="suave">/mês</span></p>
            <p>{meses(p.duracaoMeses)} · {p.formaPagamento === 'A_VISTA' ? `à vista: ${dinheiro(p.valorTotal)}` : 'pagamento mensal'}</p>
            {p.fidelidade && <p className="suave">Com fidelidade: sair antes do fim tem multa de {p.percentualMulta}% do saldo restante.</p>}
            {admin && (
              <div className="acoes">
                <button type="button" className="secundario" onClick={() => abrirEdicao(p.id)}>Editar</button>
                <button type="button" className="secundario" onClick={() => mudarSituacao(p)}>{p.ativo ? 'Inativar' : 'Reativar'}</button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </>
  );
}
