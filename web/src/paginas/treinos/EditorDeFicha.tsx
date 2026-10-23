import { useEffect, useState, type FormEvent } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { diaDe, diaSeguinte, hoje } from '../../formato';
import { chamar } from '../../sessao/api';
import { LIMITES, NOME_DO_GRUPO, type Divisao, type Exercicio, type Ficha } from './tipos';

interface ItemEditavel {
  exercicioId: string;
  series: string;
  repeticoes: string;
  carga: string;
  descansoSegundos: string;
  observacao: string;
}
interface DivisaoEditavel { nome: string; exercicios: ItemEditavel[] }

const LETRAS = 'ABCDEFG';
const itemVazio = (): ItemEditavel => ({ exercicioId: '', series: '3', repeticoes: '10', carga: '', descansoSegundos: '60', observacao: '' });

function paraEditar(divisoes: Divisao[]): DivisaoEditavel[] {
  return divisoes.map((d) => ({
    nome: d.nome,
    exercicios: d.exercicios.map((e) => ({
      exercicioId: e.exercicioId, series: String(e.series), repeticoes: e.repeticoes, carga: e.carga ?? '',
      descansoSegundos: e.descansoSegundos === null ? '' : String(e.descansoSegundos), observacao: e.observacao ?? '',
    })),
  }));
}

function paraEnviar(divisoes: DivisaoEditavel[]) {
  const ou = (v: string) => (v.trim() === '' ? null : v.trim());
  return divisoes.map((d) => ({
    nome: d.nome.trim(),
    exercicios: d.exercicios.map((e) => ({
      exercicioId: e.exercicioId, series: Number(e.series), repeticoes: e.repeticoes.trim(), carga: ou(e.carga),
      descansoSegundos: e.descansoSegundos.trim() === '' ? null : Number(e.descansoSegundos), observacao: ou(e.observacao),
    })),
  }));
}

const CAMPOS: Record<string, string> = {
  nome: 'nome', exercicioId: 'exercício', series: 'séries', repeticoes: 'repetições', carga: 'carga',
  descansoSegundos: 'descanso', observacao: 'observação', exercicios: 'exercícios', divisoes: 'divisões', revisaoEm: 'revisão',
};

/** "divisoes[0].exercicios[1].series" → "Divisão 1, exercício 2, séries". */
export function rotularCampoDaFicha(campo: string): string {
  const partes: string[] = [];
  for (const [, nome, indice] of campo.matchAll(/(\w+)(?:\[(\d+)\])?/g)) {
    if (indice !== undefined && nome === 'divisoes') partes.push(`Divisão ${Number(indice) + 1}`);
    else if (indice !== undefined && nome === 'exercicios') partes.push(`exercício ${Number(indice) + 1}`);
    else partes.push(CAMPOS[nome] ?? nome);
  }
  return partes.join(', ');
}

/** O ano que vem no mesmo dia (a revisão pode ser no máximo 1 ano à frente). */
function umAnoAFrente(): string {
  const [a, m, d] = hoje().split('-');
  return `${Number(a) + 1}-${m}-${d}`;
}

/**
 * Montar uma ficha nova (encerra a atual, que vai para o histórico) ou corrigir a atual.
 * Só exercícios ativos do catálogo; na correção, os que já estavam na ficha continuam, mesmo
 * inativados depois.
 */
export function EditorDeFicha({ base, correcao = false, titulo, aoSalvar, aoCancelar }: {
  base?: Ficha;
  /** Correção da atual: mantém os exercícios dela que saíram do catálogo. Na ficha nova, não. */
  correcao?: boolean;
  titulo: string;
  aoSalvar: (corpo: { revisaoEm: string | null; divisoes: ReturnType<typeof paraEnviar> }) => Promise<void>;
  aoCancelar: () => void;
}) {
  const [catalogo, setCatalogo] = useState<Exercicio[]>([]);
  // A revisão é da ficha: a nova começa sem (a da atual pode até já ter passado); a correção mantém.
  const [revisaoEm, setRevisaoEm] = useState(correcao ? base?.revisaoEm ?? '' : '');
  const [divisoes, setDivisoes] = useState<DivisaoEditavel[]>(
    base ? paraEditar(base.divisoes) : [{ nome: 'A', exercicios: [itemVazio()] }]);
  const [erro, setErro] = useState<unknown>(null);
  const [enviando, setEnviando] = useState(false);

  useEffect(() => {
    chamar<Exercicio[]>('/api/exercicios').then((ativos) => {
      setCatalogo(ativos);
      // Ficha nova a partir da atual: exercício que saiu do catálogo fica para escolher de novo.
      if (!correcao) {
        const ids = new Set(ativos.map((e) => e.id));
        setDivisoes((atuais) => atuais.map((d) => ({
          ...d, exercicios: d.exercicios.map((e) => (ids.has(e.exercicioId) ? e : { ...e, exercicioId: '' })),
        })));
      }
    }).catch(setErro);
  }, [correcao]);

  // Na correção, os exercícios que já estão na ficha aparecem, mesmo se saíram do catálogo.
  const daFicha = new Map(correcao
    ? base?.divisoes.flatMap((d) => d.exercicios.map((e) => [e.exercicioId, e.nome] as const)) ?? [] : []);
  const opcoes = [
    ...catalogo.map((e) => ({ id: e.id, rotulo: `${e.nome} (${NOME_DO_GRUPO[e.grupoMuscular]})` })),
    ...[...daFicha].filter(([id]) => !catalogo.some((e) => e.id === id)).map(([id, nome]) => ({ id, rotulo: `${nome} (inativo)` })),
  ];

  // A revisão é depois do dia da montagem: amanhã na ficha nova, o dia seguinte ao da atual na correção.
  const revisaoMinima = diaSeguinte(correcao && base ? diaDe(base.montadaEm) : hoje());

  const mudarDivisao = (i: number, mudanca: Partial<DivisaoEditavel>) =>
    setDivisoes(divisoes.map((d, j) => (j === i ? { ...d, ...mudanca } : d)));
  const mudarItem = (i: number, k: number, mudanca: Partial<ItemEditavel>) =>
    mudarDivisao(i, { exercicios: divisoes[i].exercicios.map((e, j) => (j === k ? { ...e, ...mudanca } : e)) });
  const moverItem = (i: number, k: number, passo: -1 | 1) => {
    const lista = [...divisoes[i].exercicios];
    [lista[k], lista[k + passo]] = [lista[k + passo], lista[k]];
    mudarDivisao(i, { exercicios: lista });
  };

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    setErro(null);
    setEnviando(true);
    try {
      await aoSalvar({ revisaoEm: revisaoEm || null, divisoes: paraEnviar(divisoes) });
    } catch (e) {
      setErro(e);
    } finally {
      setEnviando(false);
    }
  }

  const incompleta = divisoes.some((d) => !d.nome.trim() || d.exercicios.length === 0
    || d.exercicios.some((e) => !e.exercicioId || !e.repeticoes.trim() || !e.series));

  return (
    <form className="editor-ficha" onSubmit={enviar} aria-label={titulo} noValidate>
      <h3>{titulo}</h3>
      <label className="curto">Revisão em (opcional, até 1 ano)
        <input type="date" value={revisaoEm} min={revisaoMinima} max={umAnoAFrente()} onChange={(e) => setRevisaoEm(e.target.value)} />
      </label>

      {divisoes.map((divisao, i) => (
        <fieldset key={i} className="divisao">
          <legend>Divisão {i + 1}</legend>
          <div className="cabecalho">
            <label className="curto">Nome<input value={divisao.nome} maxLength={40} onChange={(e) => mudarDivisao(i, { nome: e.target.value })} /></label>
            {divisoes.length > 1 && (
              <button type="button" className="perigo" onClick={() => setDivisoes(divisoes.filter((_, j) => j !== i))}>
                Remover divisão {divisao.nome}
              </button>
            )}
          </div>
          {divisao.exercicios.map((item, k) => (
            <div key={k} className="item-ficha" role="group" aria-label={`Exercício ${k + 1} da divisão ${divisao.nome}`}>
              <label className="largo">Exercício
                <select aria-label="Exercício" value={item.exercicioId} onChange={(e) => mudarItem(i, k, { exercicioId: e.target.value })}>
                  <option value="" disabled>Escolha…</option>
                  {opcoes.map((o) => <option key={o.id} value={o.id}>{o.rotulo}</option>)}
                </select>
              </label>
              <label>Séries<input type="number" min={1} max={LIMITES.series} value={item.series}
                                  onChange={(e) => mudarItem(i, k, { series: e.target.value })} /></label>
              <label>Repetições<input value={item.repeticoes} maxLength={20} placeholder="10 ou 8-12"
                                      onChange={(e) => mudarItem(i, k, { repeticoes: e.target.value })} /></label>
              <label>Carga<input value={item.carga} maxLength={30} placeholder="20 kg"
                                 onChange={(e) => mudarItem(i, k, { carga: e.target.value })} /></label>
              <label>Descanso (s)<input type="number" min={0} max={LIMITES.descanso} value={item.descansoSegundos}
                                        onChange={(e) => mudarItem(i, k, { descansoSegundos: e.target.value })} /></label>
              <label className="largo">Observação<input value={item.observacao} maxLength={500}
                                                        onChange={(e) => mudarItem(i, k, { observacao: e.target.value })} /></label>
              <div className="acoes largo">
                <button type="button" className="secundario" disabled={k === 0} onClick={() => moverItem(i, k, -1)}>Subir</button>
                <button type="button" className="secundario" disabled={k === divisao.exercicios.length - 1}
                        onClick={() => moverItem(i, k, 1)}>Descer</button>
                {divisao.exercicios.length > 1 && (
                  <button type="button" className="perigo"
                          onClick={() => mudarDivisao(i, { exercicios: divisao.exercicios.filter((_, j) => j !== k) })}>
                    Remover exercício
                  </button>
                )}
              </div>
            </div>
          ))}
          {divisao.exercicios.length < LIMITES.exerciciosPorDivisao && (
            <button type="button" className="secundario"
                    onClick={() => mudarDivisao(i, { exercicios: [...divisao.exercicios, itemVazio()] })}>
              Adicionar exercício à divisão {divisao.nome}
            </button>
          )}
        </fieldset>
      ))}
      {divisoes.length < LIMITES.divisoes && (
        <button type="button" className="secundario"
                onClick={() => setDivisoes([...divisoes, { nome: LETRAS[divisoes.length] ?? '', exercicios: [itemVazio()] }])}>
          Adicionar divisão
        </button>
      )}

      <MensagemDeErro erro={erro} rotular={rotularCampoDaFicha} />
      <div className="acoes">
        <button type="submit" disabled={enviando || incompleta}>{enviando ? 'Salvando…' : 'Salvar ficha'}</button>
        <button type="button" className="secundario" onClick={aoCancelar}>Cancelar</button>
      </div>
    </form>
  );
}
