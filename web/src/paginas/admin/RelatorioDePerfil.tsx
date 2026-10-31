import { useEffect, useState } from 'react';
import { IDENTIDADES_DE_GENERO, ORIENTACOES_SEXUAIS } from '../../componentes/Identidade';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { data } from '../../formato';
import { NOME_DO_PERFIL } from '../../estrutura/menu';
import { chamar, type Perfil } from '../../sessao/api';

type Grupo = 'ALUNO' | 'EQUIPE';

interface Resumo {
  total: number;
  porFaixa: Record<string, number>;
  porIdentidadeDeGenero: Record<string, number>;
  porOrientacaoSexual: Record<string, number>;
}

interface Pessoa {
  grupo: Grupo;
  id: string;
  nome: string;
  perfil: Perfil | null;
  ativo: boolean;
  dataNascimento: string | null;
  idade: number | null;
  faixa: string;
  identidadeDeGenero: string | null;
  identidadeDeGeneroOutra: string | null;
  orientacaoSexual: string | null;
  orientacaoSexualOutra: string | null;
}

interface Relatorio {
  dia: string;
  comInativos: boolean;
  alunos: Resumo;
  equipe: Resumo;
  pessoas: Pessoa[];
}

export const FAIXAS_ETARIAS: Record<string, string> = {
  ATE_17: 'Até 17 anos', DE_18_A_29: '18 a 29', DE_30_A_39: '30 a 39', DE_40_A_49: '40 a 49', DE_50_A_59: '50 a 59',
  A_PARTIR_DE_60: '60 ou mais', SEM_DATA: 'Sem data de nascimento',
};
const NAO_RESPONDIDO = 'Não respondido';
// Nas contagens, "Outra" sem o "(descrever)" do formulário.
const nomeNaContagem = (nomes: Record<string, string>) => (c: string) =>
  c === 'NAO_RESPONDIDO' ? NAO_RESPONDIDO : (nomes[c] ?? c).replace(' (descrever)', '');
// Na lista, "outra" mostra o que a pessoa escreveu.
const descrever = (codigo: string | null, outra: string | null, nomes: Record<string, string>) =>
  codigo === null ? NAO_RESPONDIDO : codigo === 'OUTRA' ? `Outra: ${outra}` : nomes[codigo] ?? codigo;
const porcento = (n: number, total: number) => (total === 0 ? '—' : `${Math.round((n * 100) / total)}%`);

function Contagem({ titulo, contagem, total, nome }: {
  titulo: string; contagem: Record<string, number>; total: number; nome: (codigo: string) => string;
}) {
  return (
    <table className="tabela" aria-label={titulo}>
      <thead><tr><th>{titulo}</th><th>Pessoas</th><th>%</th></tr></thead>
      <tbody>
        {Object.entries(contagem).map(([codigo, n]) => (
          <tr key={codigo}><td>{nome(codigo)}</td><td>{n}</td><td>{porcento(n, total)}</td></tr>
        ))}
      </tbody>
    </table>
  );
}

function Grupo({ titulo, resumo }: { titulo: string; resumo: Resumo }) {
  return (
    <section className="secao">
      <h2>{titulo} ({resumo.total})</h2>
      <Contagem titulo={`Faixa etária: ${titulo.toLowerCase()}`} contagem={resumo.porFaixa} total={resumo.total}
                nome={(c) => FAIXAS_ETARIAS[c] ?? c} />
      <Contagem titulo={`Identidade de gênero: ${titulo.toLowerCase()}`} contagem={resumo.porIdentidadeDeGenero}
                total={resumo.total} nome={nomeNaContagem(IDENTIDADES_DE_GENERO)} />
      <Contagem titulo={`Orientação sexual: ${titulo.toLowerCase()}`} contagem={resumo.porOrientacaoSexual}
                total={resumo.total} nome={nomeNaContagem(ORIENTACOES_SEXUAIS)} />
    </section>
  );
}

/**
 * Relatório de idades e gênero (ADR 0048, só o ADMIN): quantos alunos e quantos da equipe há em cada faixa etária,
 * identidade de gênero e orientação sexual, e a lista de todos. Dado sensível: cada abertura fica no registro de
 * acessos.
 */
export function RelatorioDePerfil() {
  const [comInativos, setComInativos] = useState(false);
  const [grupo, setGrupo] = useState<'' | Grupo>('');
  const [relatorio, setRelatorio] = useState<Relatorio | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => {
    let atual = true;
    setErro(null);
    chamar<Relatorio>(`/api/relatorios/idades-e-genero?inativos=${comInativos}`)
      .then((r) => { if (atual) setRelatorio(r); }).catch((e) => { if (atual) setErro(e); });
    return () => { atual = false; };
  }, [comInativos]);

  const pessoas = relatorio?.pessoas.filter((p) => !grupo || p.grupo === grupo) ?? [];

  return (
    <>
      <h1>Idades e gênero</h1>
      <p className="suave">
        Só a administração vê: a orientação sexual é dado pessoal sensível (LGPD). Cada abertura deste relatório fica
        no registro de acessos.
      </p>
      <div className="filtros">
        <label className="marcar">
          <input type="checkbox" checked={comInativos} onChange={(e) => setComInativos(e.target.checked)} />
          Incluir alunos inativos e equipe desativada
        </label>
      </div>
      <MensagemDeErro erro={erro} />
      {relatorio && (
        <>
          <p className="suave">Idades em {data(relatorio.dia)}.</p>
          <Grupo titulo="Alunos" resumo={relatorio.alunos} />
          <Grupo titulo="Equipe" resumo={relatorio.equipe} />

          <section className="secao">
            <h2>Pessoas</h2>
            <div className="filtros">
              <select aria-label="Grupo" value={grupo} onChange={(e) => setGrupo(e.target.value as '' | Grupo)}>
                <option value="">Alunos e equipe</option>
                <option value="ALUNO">Só alunos</option>
                <option value="EQUIPE">Só equipe</option>
              </select>
            </div>
            <p className="suave">{pessoas.length === 1 ? '1 pessoa' : `${pessoas.length} pessoas`}</p>
            {/* Em linhas, e não em tabela: com seis informações, a tabela não caberia no celular. */}
            <ul className="lista" aria-label="Pessoas">
              {pessoas.map((p) => (
                <li key={`${p.grupo}-${p.id}`} className="linha">
                  <span className="principal">{p.nome}</span>
                  <span className="etiqueta">{p.grupo === 'ALUNO' ? 'Aluno' : NOME_DO_PERFIL[p.perfil ?? 'ADMIN']}</span>
                  {!p.ativo && <span className="etiqueta alerta">Inativo</span>}
                  <span className="suave">{p.dataNascimento ? `${data(p.dataNascimento)} (${p.idade} anos)` : 'Sem data de nascimento'}</span>
                  <span className="suave">Gênero: {descrever(p.identidadeDeGenero, p.identidadeDeGeneroOutra, IDENTIDADES_DE_GENERO)}</span>
                  <span className="suave">Orientação: {descrever(p.orientacaoSexual, p.orientacaoSexualOutra, ORIENTACOES_SEXUAIS)}</span>
                </li>
              ))}
            </ul>
          </section>
        </>
      )}
    </>
  );
}
