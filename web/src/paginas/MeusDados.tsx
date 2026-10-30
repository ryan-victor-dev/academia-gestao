import { AvisosDaAcademia } from '../componentes/AvisosDaAcademia';
import { Identidade } from '../componentes/Identidade';
import { useUsuario } from '../sessao/contexto';

/** Os dados que a própria pessoa informa (ADR 0047): a identidade e se recebe os avisos da academia. */
export function MeusDados() {
  const usuario = useUsuario();
  const aluno = usuario.perfil === 'ALUNO';
  return (
    <>
      <h1>Meus dados</h1>
      <Identidade caminho={aluno ? '/api/alunos/me/identidade' : '/api/me/identidade'}
                  titulo="Identidade de gênero e orientação sexual" somenteLeitura={usuario.somenteLeitura} />
      <AvisosDaAcademia somenteLeitura={usuario.somenteLeitura} />
    </>
  );
}
