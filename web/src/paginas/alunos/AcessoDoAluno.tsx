import { useState } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { chamar } from '../../sessao/api';

interface Credenciais {
  email: string;
  senhaTemporaria: string;
}

/**
 * Login do aluno no app: criar o acesso ou gerar uma senha nova ("esqueci minha senha", e
 * também o celular perdido: a senha nova derruba as sessões abertas). A senha temporária
 * aparece uma vez só, aqui; o aluno troca no primeiro acesso.
 */
export function AcessoDoAluno({ alunoId, temEmail, ativo }: { alunoId: string; temEmail: boolean; ativo: boolean }) {
  const [credenciais, setCredenciais] = useState<Credenciais | null>(null);
  const [erro, setErro] = useState<unknown>(null);
  const [enviando, setEnviando] = useState(false);

  async function gerar(caminho: string) {
    setErro(null);
    setCredenciais(null);
    setEnviando(true);
    try {
      setCredenciais(await chamar<Credenciais>(caminho, { metodo: 'POST' }));
    } catch (e) {
      setErro(e);
    } finally {
      setEnviando(false);
    }
  }

  return (
    <section className="secao">
      <h2>Acesso ao app</h2>
      {!ativo && <p className="suave">Aluno inativo: o acesso dele é só de consulta; reative para gerar senha.</p>}
      {ativo && !temEmail && <p className="suave">Para criar o acesso, cadastre antes o e-mail do aluno (é o login dele).</p>}
      <div className="acoes">
        <button type="button" disabled={enviando || !temEmail || !ativo} onClick={() => gerar(`/api/alunos/${alunoId}/login`)}>
          Criar acesso
        </button>
        <button type="button" className="secundario" disabled={enviando || !ativo}
                onClick={() => gerar(`/api/alunos/${alunoId}/login/nova-senha`)}>
          Gerar senha nova
        </button>
      </div>
      <p className="suave">A senha nova encerra as sessões abertas do aluno (use também se ele perder o celular).</p>
      <MensagemDeErro erro={erro} />
      {credenciais && (
        <div className="credenciais" role="status">
          <p><strong>Passe ao aluno agora: a senha não aparece de novo.</strong> No primeiro acesso ele cria a dele.</p>
          <p>Login: <code>{credenciais.email}</code></p>
          <p>Senha temporária: <code>{credenciais.senhaTemporaria}</code></p>
          <button type="button" className="secundario" onClick={() => setCredenciais(null)}>Já passei, esconder</button>
        </div>
      )}
    </section>
  );
}
