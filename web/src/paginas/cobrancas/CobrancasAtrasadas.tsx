import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { Paginacao, type Pagina } from '../../componentes/Paginacao';
import { data, dinheiro } from '../../formato';
import { chamar } from '../../sessao/api';
import { descricao, type CobrancaAtrasada } from './tipos';

/** As em aberto já vencidas, da mais antiga: para a recepção cobrar. */
export function CobrancasAtrasadas() {
  const [numero, setNumero] = useState(0);
  const [pagina, setPagina] = useState<Pagina<CobrancaAtrasada> | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => {
    let atual = true;
    chamar<Pagina<CobrancaAtrasada>>(`/api/cobrancas/atrasadas?pagina=${numero}&tamanho=20`)
      .then((p) => { if (atual) setPagina(p); })
      .catch((e) => { if (atual) setErro(e); });
    return () => { atual = false; };
  }, [numero]);

  return (
    <>
      <h1>Cobranças atrasadas</h1>
      <p className="suave">Para receber, abra o aluno.</p>
      <MensagemDeErro erro={erro} />
      {pagina && pagina.totalElementos === 0 && <p>Nenhuma cobrança atrasada.</p>}
      {pagina && pagina.totalElementos > 0 && (
        <>
          <p className="suave">{pagina.totalElementos === 1 ? '1 cobrança' : `${pagina.totalElementos} cobranças`}</p>
          <ul className="lista">
            {pagina.conteudo.map((c) => (
              <li key={c.id}>
                <Link to={`/alunos/${c.alunoId}`}>
                  <span className="principal">{c.alunoNome ?? 'Aluno'}</span>
                  <span>{descricao(c)} · venceu em {data(c.vencimento)}</span>
                  <span className="etiqueta alerta">{c.diasDeAtraso} {c.diasDeAtraso === 1 ? 'dia' : 'dias'}</span>
                  <strong>{dinheiro(c.devidoHoje?.total ?? c.valor)}</strong>
                </Link>
              </li>
            ))}
          </ul>
          <Paginacao pagina={pagina} aoMudar={setNumero} />
        </>
      )}
    </>
  );
}
