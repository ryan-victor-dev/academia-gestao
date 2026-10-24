import { useEffect, useState, type FormEvent } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { dinheiro } from '../../formato';
import { chamarComVersao, ErroDaApi } from '../../sessao/api';

interface Configuracao {
  tipoAcrescimo: 'PERCENTUAL' | 'VALOR';
  acrescimo: number;
  jurosAoMes: number;
  diasParaBloquear: number;
  tetoDoAcrescimoPercentual: number;
}

/**
 * Regras de atraso (ADMIN; ADR 0024): acréscimo (% ou R$, sempre limitado ao teto do CDC),
 * juros ao mês e quantos dias de atraso antes de bloquear o "pode treinar". Vale para todas as
 * cobranças em aberto, inclusive as já atrasadas.
 */
export function ConfiguracaoDaCobranca() {
  const [atual, setAtual] = useState<Configuracao | null>(null);
  const [versao, setVersao] = useState<string | null>(null);
  const [form, setForm] = useState({ tipoAcrescimo: 'PERCENTUAL', acrescimo: '', jurosAoMes: '', diasParaBloquear: '' });
  const [erro, setErro] = useState<unknown>(null);
  const [salvo, setSalvo] = useState(false);
  const [enviando, setEnviando] = useState(false);

  function mostrar(dados: Configuracao, v: string | null) {
    setAtual(dados);
    setVersao(v);
    setForm({ tipoAcrescimo: dados.tipoAcrescimo, acrescimo: String(dados.acrescimo), jurosAoMes: String(dados.jurosAoMes),
      diasParaBloquear: String(dados.diasParaBloquear) });
  }

  useEffect(() => {
    chamarComVersao<Configuracao>('/api/cobrancas/configuracao').then((r) => mostrar(r.dados, r.versao)).catch(setErro);
  }, []);

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    setErro(null);
    setSalvo(false);
    setEnviando(true);
    try {
      const r = await chamarComVersao<Configuracao>('/api/cobrancas/configuracao', { metodo: 'PUT', versao: versao ?? undefined,
        corpo: { tipoAcrescimo: form.tipoAcrescimo, acrescimo: Number(form.acrescimo), jurosAoMes: Number(form.jurosAoMes),
          diasParaBloquear: Number(form.diasParaBloquear) } });
      mostrar(r.dados, r.versao);
      setSalvo(true);
    } catch (e) {
      setErro(e instanceof ErroDaApi && e.status === 412
        ? new ErroDaApi(412, 'Outra pessoa alterou a configuração. Recarregue a página para ver a atual.') : e);
    } finally {
      setEnviando(false);
    }
  }

  if (!atual) return erro ? <MensagemDeErro erro={erro} /> : <p className="suave" role="status">Carregando…</p>;
  const percentual = form.tipoAcrescimo === 'PERCENTUAL';

  return (
    <>
      <h1>Configuração da cobrança</h1>
      <p className="suave">
        Vale para todas as cobranças em aberto, inclusive as já atrasadas. O acréscimo nunca passa de
        {' '}{atual.tetoDoAcrescimoPercentual}% do valor (teto do Código de Defesa do Consumidor).
      </p>
      <form className="formulario" onSubmit={enviar} aria-label="Configuração da cobrança" noValidate>
        <label>Acréscimo por atraso
          <select aria-label="Tipo do acréscimo" value={form.tipoAcrescimo} onChange={(e) => setForm({ ...form, tipoAcrescimo: e.target.value })}>
            <option value="PERCENTUAL">Percentual do valor</option>
            <option value="VALOR">Valor fixo em reais</option>
          </select>
        </label>
        <label>{percentual ? 'Acréscimo (%)' : 'Acréscimo (R$)'}
          <input type="number" min="0" step="0.01" max={percentual ? atual.tetoDoAcrescimoPercentual : 1000} value={form.acrescimo}
                 onChange={(e) => setForm({ ...form, acrescimo: e.target.value })} />
        </label>
        <label>Juros ao mês (%)
          <input type="number" min="0" max="10" step="0.01" value={form.jurosAoMes} onChange={(e) => setForm({ ...form, jurosAoMes: e.target.value })} />
        </label>
        <label>Dias de atraso antes de bloquear
          <input type="number" min="0" max="90" step="1" value={form.diasParaBloquear}
                 onChange={(e) => setForm({ ...form, diasParaBloquear: e.target.value })} />
        </label>
        <p className="suave">
          Exemplo, numa mensalidade de {dinheiro(100)} com 30 dias de atraso: acréscimo de
          {/* Em R$ 100, o percentual vira reais (2% = R$ 2), e o teto de 2% corta os dois tipos em R$ 2. */}
          {' '}{dinheiro(Math.min(Number(form.acrescimo) || 0, atual.tetoDoAcrescimoPercentual))}
          {' '}e juros de {dinheiro(Number(form.jurosAoMes) || 0)}.
        </p>
        <MensagemDeErro erro={erro} rotulos={{ acrescimo: 'Acréscimo', jurosAoMes: 'Juros', diasParaBloquear: 'Dias para bloquear' }} />
        {salvo && <p className="sucesso" role="status">Configuração salva.</p>}
        <div className="acoes">
          <button type="submit" disabled={enviando || form.acrescimo === '' || form.jurosAoMes === '' || form.diasParaBloquear === ''}>
            {enviando ? 'Salvando…' : 'Salvar'}
          </button>
        </div>
      </form>
    </>
  );
}
