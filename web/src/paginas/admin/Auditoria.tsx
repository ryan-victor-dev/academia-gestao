import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { Paginacao, type Pagina } from '../../componentes/Paginacao';
import { dataHora } from '../../formato';
import { chamar } from '../../sessao/api';

interface Registro {
  id: number;
  momento: string;
  autorNome: string | null;
  entidade: string;
  entidadeId: string;
  acao: string;
  alteracoes: Record<string, { antes: unknown; depois: unknown }>;
}

interface Acesso {
  id: number;
  momento: string;
  tipo: 'CONSULTA_ALUNO' | 'LOGIN' | 'LOGIN_FALHO' | 'LOGIN_BLOQUEADO' | 'TROCA_SENHA_FALHA' | 'RELATORIO_PERFIL' | 'CONSULTA_FOLHA';
  usuarioNome: string | null;
  conta: string | null;
  ip: string;
  alunoId: string | null;
}

const ENTIDADES: Record<string, string> = {
  ALUNO: 'Aluno', MATRICULA: 'Matrícula', COBRANCA: 'Cobrança', CAIXA: 'Caixa', PLANO: 'Plano', USUARIO: 'Usuário',
  CONFIGURACAO: 'Configuração', EXERCICIO: 'Exercício', FICHA: 'Ficha', PAGAMENTO_ONLINE: 'Pagamento online',
  PUBLICACAO: 'Publicação', PESSOA_DA_FOLHA: 'Pessoa da folha',
};
const TIPOS_DE_ACESSO: Record<Acesso['tipo'], string> = {
  CONSULTA_ALUNO: 'Abriu o cadastro de um aluno', LOGIN: 'Entrou', LOGIN_FALHO: 'Senha errada',
  LOGIN_BLOQUEADO: 'Bloqueado por tentativas', TROCA_SENHA_FALHA: 'Troca de senha recusada',
  RELATORIO_PERFIL: 'Abriu o relatório de idades e gênero',
  CONSULTA_FOLHA: 'Consultou a folha',
};

const mostrar = (v: unknown) => (v === null || v === undefined ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v));

/** Auditoria (só o ADMIN): quem alterou o quê (antes e depois) e o registro de acessos. */
export function Auditoria() {
  const [aba, setAba] = useState<'alteracoes' | 'acessos'>('alteracoes');
  return (
    <>
      <h1>Auditoria</h1>
      <div className="abas" role="tablist" aria-label="Auditoria">
        <button type="button" role="tab" aria-selected={aba === 'alteracoes'} className={aba === 'alteracoes' ? 'aba ativa' : 'aba'}
                onClick={() => setAba('alteracoes')}>Alterações</button>
        <button type="button" role="tab" aria-selected={aba === 'acessos'} className={aba === 'acessos' ? 'aba ativa' : 'aba'}
                onClick={() => setAba('acessos')}>Acessos</button>
      </div>
      {aba === 'alteracoes' ? <Alteracoes /> : <Acessos />}
    </>
  );
}

function Alteracoes() {
  const [entidade, setEntidade] = useState('');
  const [numero, setNumero] = useState(0);
  const [pagina, setPagina] = useState<Pagina<Registro> | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => {
    let atual = true;
    const parametros = new URLSearchParams({ pagina: String(numero), tamanho: '20' });
    if (entidade) parametros.set('entidade', entidade);
    chamar<Pagina<Registro>>(`/api/auditoria?${parametros}`)
      .then((p) => { if (atual) setPagina(p); }).catch((e) => { if (atual) setErro(e); });
    return () => { atual = false; };
  }, [entidade, numero]);

  return (
    <>
      <div className="filtros">
        <label>O quê
          <select aria-label="O quê" value={entidade} onChange={(e) => { setEntidade(e.target.value); setNumero(0); }}>
            <option value="">Tudo</option>
            {Object.entries(ENTIDADES).map(([valor, nome]) => <option key={valor} value={valor}>{nome}</option>)}
          </select>
        </label>
      </div>
      <MensagemDeErro erro={erro} />
      <ul className="cartoes">
        {pagina?.conteudo.map((r) => (
          <li key={r.id} className="cartao-item">
            <div className="cabecalho">
              <strong>{ENTIDADES[r.entidade] ?? r.entidade}: {r.acao.toLowerCase().replace(/_/g, ' ')}</strong>
              <span className="suave">{dataHora(r.momento)}</span>
            </div>
            <p className="suave">Por {r.autorNome ?? 'o sistema'}
              {r.entidade === 'ALUNO' && <> · <Link to={`/alunos/${r.entidadeId}`}>abrir o aluno</Link></>}</p>
            {Object.keys(r.alteracoes).length > 0 && (
              <table className="tabela">
                <thead><tr><th>Campo</th><th>Antes</th><th>Depois</th></tr></thead>
                <tbody>
                  {Object.entries(r.alteracoes).map(([campo, { antes, depois }]) => (
                    <tr key={campo}><td>{campo}</td><td>{mostrar(antes)}</td><td>{mostrar(depois)}</td></tr>
                  ))}
                </tbody>
              </table>
            )}
          </li>
        ))}
      </ul>
      {pagina && <Paginacao pagina={pagina} aoMudar={setNumero} />}
    </>
  );
}

function Acessos() {
  const [tipo, setTipo] = useState('');
  const [conta, setConta] = useState('');
  const [numero, setNumero] = useState(0);
  const [pagina, setPagina] = useState<Pagina<Acesso> | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => {
    let atual = true;
    const espera = setTimeout(() => {
      const parametros = new URLSearchParams({ pagina: String(numero), tamanho: '20' });
      if (tipo) parametros.set('tipo', tipo);
      if (conta.trim()) parametros.set('conta', conta.trim());
      chamar<Pagina<Acesso>>(`/api/auditoria/acessos?${parametros}`)
        .then((p) => { if (atual) setPagina(p); }).catch((e) => { if (atual) setErro(e); });
    }, 300);
    return () => { atual = false; clearTimeout(espera); };
  }, [tipo, conta, numero]);

  return (
    <>
      <div className="filtros">
        <label>Tipo
          <select aria-label="Tipo" value={tipo} onChange={(e) => { setTipo(e.target.value); setNumero(0); }}>
            <option value="">Todos</option>
            {Object.entries(TIPOS_DE_ACESSO).map(([valor, nome]) => <option key={valor} value={valor}>{nome}</option>)}
          </select>
        </label>
        <label>E-mail digitado no login (exato)
          <input type="search" value={conta} onChange={(e) => { setConta(e.target.value); setNumero(0); }} />
        </label>
      </div>
      <MensagemDeErro erro={erro} />
      <ul className="lista">
        {pagina?.conteudo.map((a) => (
          <li key={a.id} className="linha">
            <span className="principal">{TIPOS_DE_ACESSO[a.tipo]}</span>
            <span>{a.usuarioNome ?? a.conta ?? '—'}</span>
            <span className="suave">{dataHora(a.momento)} · IP {a.ip}</span>
            {a.alunoId && <Link to={`/alunos/${a.alunoId}`}>aluno</Link>}
          </li>
        ))}
      </ul>
      {pagina && <Paginacao pagina={pagina} aoMudar={setNumero} />}
    </>
  );
}
