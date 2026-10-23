import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { Paginacao, type Pagina } from '../../componentes/Paginacao';
import { data } from '../../formato';
import { chamar } from '../../sessao/api';
import { useUsuario } from '../../sessao/contexto';
import type { AlunoNaLista } from './tipos';

/** Busca de alunos por nome (e por CPF, menos para o professor). */
export function ListaDeAlunos() {
  const usuario = useUsuario();
  const podeCadastrar = usuario.perfil === 'ADMIN' || usuario.perfil === 'RECEPCAO';
  const [busca, setBusca] = useState('');
  const [inativos, setInativos] = useState(false);
  const [numero, setNumero] = useState(0);
  const [pagina, setPagina] = useState<Pagina<AlunoNaLista> | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => {
    let atual = true;
    // Espera a pessoa parar de digitar antes de buscar.
    const espera = setTimeout(async () => {
      const parametros = new URLSearchParams({ pagina: String(numero), tamanho: '20', incluirInativos: String(inativos) });
      if (busca.trim()) parametros.set('busca', busca.trim());
      try {
        const resultado = await chamar<Pagina<AlunoNaLista>>(`/api/alunos?${parametros}`);
        if (atual) { setPagina(resultado); setErro(null); }
      } catch (e) {
        if (atual) setErro(e);
      }
    }, 300);
    return () => { atual = false; clearTimeout(espera); };
  }, [busca, inativos, numero]);

  return (
    <>
      <div className="cabecalho">
        <h1>Alunos</h1>
        {podeCadastrar && <Link className="botao" to="/alunos/novo">Novo aluno</Link>}
      </div>
      <div className="filtros">
        <label>
          {usuario.perfil === 'INSTRUTOR' ? 'Buscar por nome' : 'Buscar por nome ou CPF'}
          <input type="search" value={busca} onChange={(e) => { setBusca(e.target.value); setNumero(0); }} />
        </label>
        <label className="marcar">
          <input type="checkbox" checked={inativos} onChange={(e) => { setInativos(e.target.checked); setNumero(0); }} />
          Mostrar inativos
        </label>
      </div>
      <MensagemDeErro erro={erro} />
      {pagina && (
        <>
          <p className="suave">{pagina.totalElementos === 1 ? '1 aluno' : `${pagina.totalElementos} alunos`}</p>
          <ul className="lista">
            {pagina.conteudo.map((aluno) => (
              <li key={aluno.id}>
                <Link to={`/alunos/${aluno.id}`}>
                  <span className="principal">{aluno.nome}</span>
                  <span className="suave">Nascimento: {data(aluno.dataNascimento)}</span>
                  {!aluno.ativo && <span className="etiqueta">Inativo</span>}
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
