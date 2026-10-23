import { useState, type FormEvent } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { hoje } from '../../formato';
import { ROTULOS_DO_ALUNO, type DadosDoAluno } from './tipos';

const VAZIO: DadosDoAluno = { nome: '', cpf: '', email: '', telefone: '', dataNascimento: '', observacoes: '' };

/** Formulário do cadastro, para criar e para editar. */
export function FormularioDoAluno({ inicial, rotuloDoBotao, aoSalvar, aoCancelar }: {
  inicial?: DadosDoAluno;
  rotuloDoBotao: string;
  aoSalvar: (dados: DadosDoAluno) => Promise<void>;
  aoCancelar?: () => void;
}) {
  const [dados, setDados] = useState<DadosDoAluno>(inicial ?? VAZIO);
  const [erro, setErro] = useState<unknown>(null);
  const [enviando, setEnviando] = useState(false);
  const campo = (nome: keyof DadosDoAluno) => ({
    value: dados[nome],
    onChange: (e: { target: { value: string } }) => setDados({ ...dados, [nome]: e.target.value }),
  });

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    setErro(null);
    setEnviando(true);
    try {
      await aoSalvar(dados);
    } catch (e) {
      setErro(e);
    } finally {
      setEnviando(false);
    }
  }

  return (
    <form onSubmit={enviar} className="formulario" noValidate>
      <label>Nome<input required maxLength={150} {...campo('nome')} /></label>
      <label>CPF<input required inputMode="numeric" autoComplete="off" {...campo('cpf')} /></label>
      <label>E-mail<input type="email" {...campo('email')} /></label>
      <label>Telefone<input type="tel" {...campo('telefone')} /></label>
      <label>Nascimento<input type="date" max={hoje()} {...campo('dataNascimento')} /></label>
      <label className="largo">
        Observações (saúde, restrições: o professor também vê)
        <textarea rows={3} maxLength={2000} {...campo('observacoes')} />
      </label>
      <MensagemDeErro erro={erro} rotulos={ROTULOS_DO_ALUNO} />
      <div className="acoes">
        <button type="submit" disabled={enviando || !dados.nome.trim() || !dados.cpf.trim()}>
          {enviando ? 'Salvando…' : rotuloDoBotao}
        </button>
        {aoCancelar && <button type="button" className="secundario" onClick={aoCancelar}>Cancelar</button>}
      </div>
    </form>
  );
}
