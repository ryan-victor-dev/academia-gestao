import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { cpf, data, dinheiro, telefone } from '../../formato';
import { chamar } from '../../sessao/api';
import { useUsuario } from '../../sessao/contexto';
import type { Aluno } from '../alunos/tipos';
import type { Matricula } from '../alunos/MatriculasDoAluno';
import type { CobrancaDoAluno } from './MeusPagamentos';

const SITUACAO: Record<Matricula['situacao'], string> = {
  AGENDADA: 'começa em', VIGENTE: 'vale até', TRANCADA: 'trancada; vale até', ENCERRADA: 'encerrada em', CANCELADA: 'cancelada',
};

/** O início do aluno: a matrícula, o que está em aberto e o atalho para o treino. */
export function InicioDoAluno() {
  const usuario = useUsuario();
  const [matriculas, setMatriculas] = useState<Matricula[] | null>(null);
  const [cobrancas, setCobrancas] = useState<CobrancaDoAluno[] | null>(null);
  const [dados, setDados] = useState<Aluno | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => {
    chamar<Matricula[]>('/api/matriculas/me').then(setMatriculas).catch(setErro);
    chamar<CobrancaDoAluno[]>('/api/cobrancas/me').then(setCobrancas).catch(setErro);
  }, []);

  const atual = matriculas?.find((m) => m.situacao === 'VIGENTE' || m.situacao === 'TRANCADA')
    ?? matriculas?.find((m) => m.situacao === 'AGENDADA');
  const atrasadas = cobrancas?.filter((c) => c.situacao === 'ATRASADA') ?? [];
  const proxima = cobrancas?.filter((c) => c.situacao === 'PENDENTE').sort((a, b) => a.vencimento.localeCompare(b.vencimento))[0];

  return (
    <>
      <h1>Olá, {usuario.nome}</h1>
      <MensagemDeErro erro={erro} />
      {atrasadas.length > 0 && (
        <p className="aviso faixa-cartao" role="status">
          Você tem {atrasadas.length === 1 ? '1 pagamento atrasado' : `${atrasadas.length} pagamentos atrasados`}.{' '}
          <Link to="/minhas-cobrancas">Ver pagamentos</Link>
        </p>
      )}
      <ul className="atalhos">
        <li><Link to="/meu-treino">Meu treino</Link></li>
        <li><Link to="/minhas-cobrancas">Pagamentos</Link></li>
      </ul>

      <section className="secao">
        <h2>Minha matrícula</h2>
        {matriculas && !atual && <p>Nenhuma matrícula vigente. Fale com a recepção.</p>}
        {atual && (
          <p>
            <strong>{atual.planoNome}</strong> · {SITUACAO[atual.situacao]} {data(atual.situacao === 'AGENDADA' ? atual.inicio : atual.fim)}
            {' · '}{dinheiro(atual.valorMensal)}/mês
          </p>
        )}
        {proxima && <p className="suave">Próximo vencimento: {data(proxima.vencimento)} ({dinheiro(proxima.valor)})</p>}
      </section>

      <section className="secao">
        <details onToggle={(e) => {
          if ((e.target as HTMLDetailsElement).open && !dados) chamar<Aluno>('/api/alunos/me').then(setDados).catch(setErro);
        }}>
          <summary>Meus dados</summary>
          {dados && (
            <dl className="dados">
              <dt>Nome</dt><dd>{dados.nome}</dd>
              <dt>CPF</dt><dd>{cpf(dados.cpf)}</dd>
              <dt>E-mail</dt><dd>{dados.email ?? '—'}</dd>
              <dt>Telefone</dt><dd>{telefone(dados.telefone)}</dd>
              <dt>Nascimento</dt><dd>{data(dados.dataNascimento)}</dd>
            </dl>
          )}
          <p className="suave">Para corrigir algum dado, fale com a recepção.</p>
        </details>
      </section>
    </>
  );
}
