import { Identidade } from '../componentes/Identidade';
import { useUsuario } from '../sessao/contexto';

/** Os dados que a própria pessoa informa (ADR 0047). O aluno guarda no cadastro dele; a equipe, no login. */
export function MeusDados() {
  const usuario = useUsuario();
  const aluno = usuario.perfil === 'ALUNO';
  return (
    <>
      <h1>Meus dados</h1>
      <Identidade caminho={aluno ? '/api/alunos/me/identidade' : '/api/me/identidade'}
                  titulo="Identidade de gênero e orientação sexual" somenteLeitura={usuario.somenteLeitura} />
    </>
  );
}
