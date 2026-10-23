import { useNavigate } from 'react-router';
import { chamar } from '../../sessao/api';
import { FormularioDoAluno } from './FormularioDoAluno';
import { paraEnviar, type Aluno } from './tipos';

export function NovoAluno() {
  const navegar = useNavigate();
  return (
    <>
      <h1>Novo aluno</h1>
      <FormularioDoAluno
        rotuloDoBotao="Cadastrar"
        aoCancelar={() => navegar('/alunos')}
        aoSalvar={async (dados) => {
          const aluno = await chamar<Aluno>('/api/alunos', { metodo: 'POST', corpo: paraEnviar(dados) });
          navegar(`/alunos/${aluno.id}`, { replace: true });
        }}
      />
    </>
  );
}
