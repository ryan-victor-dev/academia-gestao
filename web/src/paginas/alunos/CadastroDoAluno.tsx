import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { cpf, data, dataHora, telefone } from '../../formato';
import { chamar, chamarComVersao, ErroDaApi } from '../../sessao/api';
import { AcessoDoAluno } from './AcessoDoAluno';
import { FormularioDoAluno } from './FormularioDoAluno';
import { MatriculasDoAluno } from './MatriculasDoAluno';
import { CobrancasDoAluno } from '../cobrancas/CobrancasDoAluno';
import { NaoEncontrada } from '../NaoEncontrada';
import { paraEnviar, type Aluno, type AlunoParaInstrutor } from './tipos';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * O cadastro aberto (cada abertura fica no registro de acessos), com as ações do perfil. Um id
 * que não é UUID (ex.: /alunos/novo para quem não cadastra) é "não encontrada", sem ir à API.
 */
export function CadastroDoAluno() {
  const { id = '' } = useParams();
  return UUID.test(id) ? <Cadastro id={id} /> : <NaoEncontrada />;
}

function Cadastro({ id }: { id: string }) {
  const [aluno, setAluno] = useState<Aluno | AlunoParaInstrutor | null>(null);
  const [versao, setVersao] = useState<string | null>(null);
  const [podeTreinar, setPodeTreinar] = useState<boolean | null>(null);
  const [editando, setEditando] = useState(false);
  // Muda a cada ação na matrícula: as cobranças são recarregadas (a matrícula as gera e cancela).
  const [mudancas, setMudancas] = useState(0);
  const [erro, setErro] = useState<unknown>(null);

  const carregar = useCallback(async () => {
    try {
      const { dados, versao } = await chamarComVersao<Aluno | AlunoParaInstrutor>(`/api/alunos/${id}`);
      setAluno(dados);
      setVersao(versao);
      setErro(null);
    } catch (e) {
      setErro(e);
    }
  }, [id]);

  const conferirSePodeTreinar = useCallback(async () => {
    try {
      setPodeTreinar((await chamar<{ podeTreinar: boolean }>(`/api/matriculas/vigente?alunoId=${id}`)).podeTreinar);
    } catch {
      setPodeTreinar(null);
    }
  }, [id]);

  useEffect(() => { void carregar(); void conferirSePodeTreinar(); }, [carregar, conferirSePodeTreinar]);

  async function mudarSituacao(acao: 'inativar' | 'reativar') {
    setErro(null);
    try {
      const { dados, versao } = await chamarComVersao<Aluno>(`/api/alunos/${id}/${acao}`, { metodo: 'POST' });
      setAluno(dados);
      setVersao(versao);
    } catch (e) {
      setErro(e);
    }
  }

  if (!aluno) {
    return erro ? <MensagemDeErro erro={erro} /> : <p className="suave" role="status">Carregando…</p>;
  }
  // O cadastro completo só vem para a recepção e o ADMIN (o professor recebe a visão reduzida):
  // é ele que libera a edição, as matrículas e o acesso.
  const completo = 'cpf' in aluno ? aluno : null;

  return (
    <>
      <p><Link to="/alunos">← Alunos</Link></p>
      <div className="cabecalho">
        <h1>{aluno.nome}</h1>
        {!aluno.ativo && <span className="etiqueta">Inativo</span>}
        {podeTreinar !== null && (
          <span className={podeTreinar ? 'etiqueta ok' : 'etiqueta alerta'}>
            {podeTreinar ? 'Pode treinar' : 'Não pode treinar'}
          </span>
        )}
      </div>

      {editando && completo ? (
        <FormularioDoAluno
          rotuloDoBotao="Salvar"
          inicial={{
            nome: completo.nome, cpf: completo.cpf, email: completo.email ?? '', telefone: completo.telefone ?? '',
            dataNascimento: completo.dataNascimento ?? '', observacoes: completo.observacoes ?? '',
          }}
          aoCancelar={() => setEditando(false)}
          aoSalvar={async (dados) => {
            try {
              const salvo = await chamarComVersao<Aluno>(`/api/alunos/${id}`,
                { metodo: 'PUT', corpo: paraEnviar(dados), versao: versao ?? undefined });
              setAluno(salvo.dados);
              setVersao(salvo.versao);
              setEditando(false);
            } catch (e) {
              if (e instanceof ErroDaApi && e.status === 412) {
                throw new ErroDaApi(412, 'Outra pessoa alterou este cadastro enquanto você editava. '
                  + 'Cancele e abra de novo para ver a versão atual.');
              }
              throw e;
            }
          }}
        />
      ) : (
        <dl className="dados">
          {completo && <><dt>CPF</dt><dd>{cpf(completo.cpf)}</dd></>}
          <dt>Nascimento</dt><dd>{data(aluno.dataNascimento)}</dd>
          {completo && <><dt>E-mail</dt><dd>{completo.email ?? '—'}</dd></>}
          {completo && <><dt>Telefone</dt><dd>{telefone(completo.telefone)}</dd></>}
          <dt>Observações</dt><dd className="texto">{aluno.observacoes ?? '—'}</dd>
          {completo && <><dt>Cadastrado em</dt><dd>{dataHora(completo.criadoEm)}</dd></>}
        </dl>
      )}

      <MensagemDeErro erro={erro} />

      {completo && !editando && (
        <div className="acoes">
          <button type="button" onClick={() => setEditando(true)}>Editar</button>
          {aluno.ativo
            ? <button type="button" className="secundario" onClick={() => mudarSituacao('inativar')}>Inativar</button>
            : <button type="button" className="secundario" onClick={() => mudarSituacao('reativar')}>Reativar</button>}
        </div>
      )}

      {completo && (
        <>
          <MatriculasDoAluno alunoId={completo.id} aoMudar={() => { void conferirSePodeTreinar(); setMudancas((n) => n + 1); }} />
          <CobrancasDoAluno alunoId={completo.id} atualizar={mudancas} />
          <AcessoDoAluno alunoId={completo.id} temEmail={!!completo.email} ativo={completo.ativo} />
        </>
      )}
    </>
  );
}
