import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { data, dinheiro, hoje } from '../../formato';
import { chamar } from '../../sessao/api';

type Situacao = 'AGENDADA' | 'VIGENTE' | 'TRANCADA' | 'ENCERRADA' | 'CANCELADA';

interface Trancamento { inicio: string; voltaPrevista: string; volta: string | null; dias: number; motivo: string }

export interface Matricula {
  id: string;
  planoNome: string;
  valorMensal: number;
  duracaoMeses: number;
  valorTotal: number;
  fidelidade: boolean;
  percentualMulta: number | null;
  formaPagamento: 'MENSAL' | 'A_VISTA';
  inicio: string;
  fim: string;
  situacao: Situacao;
  trancamentos: Trancamento[];
  motivoCancelamento: string | null;
  multa: number | null;
}

interface Plano { id: string; nome: string; valorMensal: number; duracaoMeses: number; ativo: boolean }

const NOME_DA_SITUACAO: Record<Situacao, string> = {
  AGENDADA: 'Agendada', VIGENTE: 'Vigente', TRANCADA: 'Trancada', ENCERRADA: 'Encerrada', CANCELADA: 'Cancelada',
};

type Acao = { tipo: 'nova' } | { tipo: 'cancelar' | 'trancar' | 'trocar'; matricula: Matricula };

/** Matrículas do aluno (recepção e ADMIN): histórico e as ações sobre a atual. */
export function MatriculasDoAluno({ alunoId, aoMudar }: { alunoId: string; aoMudar: () => void }) {
  const [matriculas, setMatriculas] = useState<Matricula[] | null>(null);
  const [planos, setPlanos] = useState<Plano[]>([]);
  const [acao, setAcao] = useState<Acao | null>(null);
  const [erro, setErro] = useState<unknown>(null);

  const carregar = useCallback(async () => {
    try {
      setMatriculas(await chamar<Matricula[]>(`/api/matriculas?alunoId=${alunoId}`));
    } catch (e) {
      setErro(e);
    }
  }, [alunoId]);

  useEffect(() => {
    void carregar();
    chamar<Plano[]>('/api/planos').then(setPlanos).catch(() => setPlanos([]));
  }, [carregar]);

  /** Faz a ação, recarrega o histórico e avisa a página (o "pode treinar" muda). */
  async function executar(caminho: string, corpo?: unknown) {
    setErro(null);
    try {
      await chamar(caminho, { metodo: 'POST', corpo });
      setAcao(null);
      await carregar();
      aoMudar();
    } catch (e) {
      setErro(e);
    }
  }

  const temAtual = matriculas?.some((m) => m.situacao === 'VIGENTE' || m.situacao === 'TRANCADA' || m.situacao === 'AGENDADA');

  return (
    <section className="secao">
      <div className="cabecalho">
        <h2>Matrículas</h2>
        <button type="button" onClick={() => setAcao({ tipo: 'nova' })}>Nova matrícula</button>
      </div>
      {temAtual && acao?.tipo === 'nova' && (
        <p className="suave">O aluno já tem matrícula vigente ou agendada: a nova só pode começar depois do fim dela.</p>
      )}

      {acao?.tipo === 'nova' && (
        <Formulario titulo="Nova matrícula" aoCancelar={() => setAcao(null)}
                    aoEnviar={(f) => executar('/api/matriculas', { alunoId, planoId: f.get('planoId'), inicio: f.get('inicio') || null })}>
          <label>Plano<SeletorDePlano rotulo="Plano" planos={planos} /></label>
          <label>Início (vazio: hoje)<input type="date" name="inicio" min={hoje()} /></label>
        </Formulario>
      )}

      <MensagemDeErro erro={erro} rotulos={{ planoId: 'Plano', motivo: 'Motivo', voltaPrevista: 'Volta prevista', inicio: 'Início' }} />

      {matriculas?.length === 0 && <p className="suave">Nenhuma matrícula.</p>}
      <ul className="cartoes">
        {matriculas?.map((m) => (
          <li key={m.id} className="cartao-item">
            <div className="cabecalho">
              <strong>{m.planoNome}</strong>
              <span className={`etiqueta situacao-${m.situacao.toLowerCase()}`}>{NOME_DA_SITUACAO[m.situacao]}</span>
            </div>
            <p>{data(m.inicio)} a {data(m.fim)} · {dinheiro(m.valorMensal)}/mês · {m.duracaoMeses} {m.duracaoMeses === 1 ? 'mês' : 'meses'}
              {' · '}{m.formaPagamento === 'A_VISTA' ? `à vista (${dinheiro(m.valorTotal)})` : 'mensal'}
              {m.fidelidade && ` · fidelidade (multa de ${m.percentualMulta}%)`}</p>
            {m.trancamentos.map((t) => (
              <p key={t.inicio} className="suave">
                Trancada de {data(t.inicio)} {t.volta ? `a ${data(t.volta)}` : `(volta prevista ${data(t.voltaPrevista)})`}: {t.motivo}
              </p>
            ))}
            {m.situacao === 'CANCELADA' && <p className="suave">Cancelada: {m.motivoCancelamento}
              {m.multa ? ` · multa de ${dinheiro(m.multa)}` : ''}</p>}

            <div className="acoes">
              {m.situacao === 'VIGENTE' && <>
                <button type="button" className="secundario" onClick={() => setAcao({ tipo: 'trancar', matricula: m })}>Trancar</button>
                <button type="button" className="secundario" onClick={() => setAcao({ tipo: 'trocar', matricula: m })}>Trocar plano</button>
              </>}
              {m.situacao === 'TRANCADA' && (
                <button type="button" className="secundario" onClick={() => executar(`/api/matriculas/${m.id}/registrar-volta`)}>
                  Registrar volta
                </button>
              )}
              {(m.situacao === 'VIGENTE' || m.situacao === 'AGENDADA') && (
                <button type="button" className="perigo" onClick={() => setAcao({ tipo: 'cancelar', matricula: m })}>Cancelar</button>
              )}
            </div>

            {acao && acao.tipo !== 'nova' && acao.matricula.id === m.id && (
              <>
                {acao.tipo === 'cancelar' && (
                  <Formulario titulo="Cancelar matrícula" rotulo="Confirmar cancelamento" aoCancelar={() => setAcao(null)}
                              aoEnviar={(f) => executar(`/api/matriculas/${m.id}/cancelar`, { motivo: f.get('motivo') })}>
                    {m.fidelidade && m.situacao === 'VIGENTE' && (
                      <p className="aviso">Plano com fidelidade: o cancelamento gera multa de {m.percentualMulta}% do saldo restante.</p>
                    )}
                    <label>Motivo<input name="motivo" required maxLength={500} /></label>
                  </Formulario>
                )}
                {acao.tipo === 'trancar' && (
                  <Formulario titulo="Trancar matrícula" rotulo="Trancar" aoCancelar={() => setAcao(null)}
                              aoEnviar={(f) => executar(`/api/matriculas/${m.id}/trancar`,
                                { inicio: f.get('inicio') || null, voltaPrevista: f.get('voltaPrevista'), motivo: f.get('motivo') })}>
                    <label>Início (vazio: hoje)<input type="date" name="inicio" min={hoje()} /></label>
                    <label>Volta prevista<input type="date" name="voltaPrevista" required min={hoje()} /></label>
                    <label>Motivo<input name="motivo" required maxLength={500} /></label>
                    <p className="suave">De 7 a 90 dias (no máximo 90 somados na matrícula). O fim avança pelos dias parados.</p>
                  </Formulario>
                )}
                {acao.tipo === 'trocar' && (
                  <Formulario titulo="Trocar de plano" rotulo="Trocar" aoCancelar={() => setAcao(null)}
                              aoEnviar={(f) => executar(`/api/matriculas/${m.id}/trocar-plano`, { planoId: f.get('planoId') })}>
                    <label>Novo plano<SeletorDePlano rotulo="Novo plano" planos={planos.filter((p) => p.nome !== m.planoNome)} /></label>
                    <p className="suave">A atual termina ontem e a nova começa hoje.</p>
                  </Formulario>
                )}
              </>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

/** O nome vem do aria-label: sem ele, o leitor de tela leria o rótulo junto com a opção escolhida. */
function SeletorDePlano({ planos, rotulo }: { planos: Plano[]; rotulo: string }) {
  return (
    <select name="planoId" required defaultValue="" aria-label={rotulo}>
      <option value="" disabled>Escolha…</option>
      {planos.filter((p) => p.ativo).map((p) => (
        <option key={p.id} value={p.id}>
          {p.nome} — {dinheiro(p.valorMensal)}/mês, {p.duracaoMeses} {p.duracaoMeses === 1 ? 'mês' : 'meses'}
        </option>
      ))}
    </select>
  );
}

/** Formulário curto de uma ação; os campos vêm pelo nome (FormData). */
function Formulario({ titulo, rotulo = 'Salvar', children, aoEnviar, aoCancelar }: {
  titulo: string; rotulo?: string; children: ReactNode;
  aoEnviar: (f: FormData) => Promise<void>; aoCancelar: () => void;
}) {
  const [enviando, setEnviando] = useState(false);
  async function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    setEnviando(true);
    try {
      await aoEnviar(new FormData(evento.currentTarget));
    } finally {
      setEnviando(false);
    }
  }
  return (
    <form className="formulario acao" onSubmit={enviar} aria-label={titulo}>
      <h3>{titulo}</h3>
      {children}
      <div className="acoes">
        <button type="submit" disabled={enviando}>{enviando ? 'Enviando…' : rotulo}</button>
        <button type="button" className="secundario" onClick={aoCancelar}>Voltar</button>
      </div>
    </form>
  );
}
