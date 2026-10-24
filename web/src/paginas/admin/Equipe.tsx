import { useCallback, useEffect, useState } from 'react';
import { FormularioDeAcao } from '../../componentes/FormularioDeAcao';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { NOME_DO_PERFIL } from '../../estrutura/menu';
import { chamar, type Perfil, type Usuario } from '../../sessao/api';
import { useUsuario } from '../../sessao/contexto';

const PERFIS_DA_EQUIPE: Perfil[] = ['RECEPCAO', 'INSTRUTOR', 'ADMIN'];

type Acao = { tipo: 'novo' } | { tipo: 'editar'; usuario: Usuario };

/**
 * A equipe (só o ADMIN): criar o login com senha temporária, mudar nome e perfil, desativar
 * quem saiu, reativar e gerar senha nova. A API nunca redefine a senha de um ADMIN e não deixa
 * ninguém desativar a si mesmo nem mudar o próprio perfil; a tela nem oferece.
 */
export function Equipe() {
  const eu = useUsuario();
  const [usuarios, setUsuarios] = useState<Usuario[] | null>(null);
  const [comAlunos, setComAlunos] = useState(false);
  const [acao, setAcao] = useState<Acao | null>(null);
  const [senha, setSenha] = useState<{ email: string; senhaTemporaria: string } | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  const carregar = useCallback(async () => {
    try {
      setUsuarios(await chamar<Usuario[]>('/api/usuarios'));
    } catch (e) {
      setErro(e);
    }
  }, []);
  useEffect(() => { void carregar(); }, [carregar]);

  async function executar(fazer: () => Promise<void>) {
    setErro(null);
    try {
      await fazer();
      setAcao(null);
      await carregar();
    } catch (e) {
      setErro(e);
    }
  }

  const visiveis = usuarios?.filter((u) => comAlunos || u.perfil !== 'ALUNO');

  return (
    <>
      <div className="cabecalho">
        <h1>Equipe</h1>
        <button type="button" onClick={() => { setSenha(null); setAcao({ tipo: 'novo' }); }}>Novo login</button>
      </div>

      {acao?.tipo === 'novo' && (
        <FormularioDeAcao titulo="Novo login da equipe" rotulo="Criar" aoCancelar={() => setAcao(null)}
                          aoEnviar={(f) => executar(async () => {
                            const criado = await chamar<{ email: string; senhaTemporaria: string }>('/api/usuarios',
                              { metodo: 'POST', corpo: { nome: f.get('nome'), email: f.get('email'), perfil: f.get('perfil') } });
                            setSenha(criado);
                          })}>
          <label>Nome<input name="nome" required maxLength={150} /></label>
          <label>E-mail (é o login)<input name="email" type="email" required maxLength={150} /></label>
          <label>Perfil<SeletorDePerfil /></label>
        </FormularioDeAcao>
      )}
      {acao?.tipo === 'editar' && (
        <FormularioDeAcao titulo={`Editar ${acao.usuario.nome}`} aoCancelar={() => setAcao(null)}
                          aoEnviar={(f) => executar(async () => {
                            await chamar(`/api/usuarios/${acao.usuario.id}`,
                              { metodo: 'PUT', corpo: { nome: f.get('nome'), perfil: f.get('perfil') ?? acao.usuario.perfil } });
                          })}>
          <label>Nome<input name="nome" required maxLength={150} defaultValue={acao.usuario.nome} /></label>
          {acao.usuario.id !== eu.id && acao.usuario.perfil !== 'ALUNO' && (
            <label>Perfil<SeletorDePerfil inicial={acao.usuario.perfil} /></label>
          )}
          <p className="suave">Mudar o perfil encerra as sessões da pessoa: ela entra de novo com o perfil novo.</p>
        </FormularioDeAcao>
      )}

      <MensagemDeErro erro={erro} rotulos={{ nome: 'Nome', email: 'E-mail', perfil: 'Perfil' }} />
      {senha && (
        <div className="credenciais" role="status">
          <p><strong>Passe agora: a senha não aparece de novo.</strong> No primeiro acesso a pessoa cria a dela.</p>
          <p>Login: <code>{senha.email}</code></p>
          <p>Senha temporária: <code>{senha.senhaTemporaria}</code></p>
          <button type="button" className="secundario" onClick={() => setSenha(null)}>Já passei, esconder</button>
        </div>
      )}

      <label className="marcar">
        <input type="checkbox" checked={comAlunos} onChange={(e) => setComAlunos(e.target.checked)} />
        Mostrar também os logins de alunos
      </label>
      <ul className="lista">
        {visiveis?.map((u) => (
          <li key={u.id} className="linha">
            <span className="principal">{u.nome}{u.id === eu.id && ' (você)'}</span>
            <span className="suave">{u.email}</span>
            <span className="etiqueta">{NOME_DO_PERFIL[u.perfil]}</span>
            {!u.ativo && <span className="etiqueta alerta">Desativado</span>}
            {u.precisaTrocarSenha && <span className="etiqueta">Senha temporária</span>}
            <span className="acoes-linha">
              <button type="button" className="secundario" onClick={() => { setSenha(null); setAcao({ tipo: 'editar', usuario: u }); }}>
                Editar
              </button>
              {u.id !== eu.id && (u.ativo
                ? <button type="button" className="perigo" onClick={() => executar(() => chamar(`/api/usuarios/${u.id}/desativar`, { metodo: 'POST' }))}>Desativar</button>
                : <button type="button" className="secundario" onClick={() => executar(() => chamar(`/api/usuarios/${u.id}/reativar`, { metodo: 'POST' }))}>Reativar</button>)}
              {u.perfil !== 'ADMIN' && u.ativo && (
                <button type="button" className="secundario" onClick={() => executar(async () => {
                  setSenha(await chamar(`/api/usuarios/${u.id}/nova-senha`, { metodo: 'POST' }));
                })}>Senha nova</button>
              )}
            </span>
          </li>
        ))}
      </ul>
    </>
  );
}

function SeletorDePerfil({ inicial }: { inicial?: Perfil }) {
  return (
    <select name="perfil" required defaultValue={inicial ?? ''} aria-label="Perfil">
      <option value="" disabled>Escolha…</option>
      {PERFIS_DA_EQUIPE.map((p) => <option key={p} value={p}>{NOME_DO_PERFIL[p]}</option>)}
    </select>
  );
}
