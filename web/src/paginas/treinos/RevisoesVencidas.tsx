import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { Paginacao, type Pagina } from '../../componentes/Paginacao';
import { data } from '../../formato';
import { chamar } from '../../sessao/api';

interface RevisaoVencida { fichaId: string; alunoId: string; alunoNome: string; revisaoEm: string }

/** Fichas atuais com a revisão até hoje, da mais antiga (só de alunos ativos). */
export function RevisoesVencidas() {
  const [numero, setNumero] = useState(0);
  const [pagina, setPagina] = useState<Pagina<RevisaoVencida> | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => {
    let atual = true;
    chamar<Pagina<RevisaoVencida>>(`/api/fichas/revisoes-vencidas?pagina=${numero}&tamanho=20`)
      .then((p) => { if (atual) setPagina(p); })
      .catch((e) => { if (atual) setErro(e); });
    return () => { atual = false; };
  }, [numero]);

  return (
    <>
      <h1>Revisões vencidas</h1>
      <MensagemDeErro erro={erro} />
      {pagina?.totalElementos === 0 && <p>Nenhuma ficha com revisão vencida.</p>}
      {pagina && pagina.totalElementos > 0 && (
        <>
          <ul className="lista">
            {pagina.conteudo.map((r) => (
              <li key={r.fichaId}>
                <Link to={`/alunos/${r.alunoId}`}>
                  <span className="principal">{r.alunoNome}</span>
                  <span className="etiqueta alerta">Revisão em {data(r.revisaoEm)}</span>
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
