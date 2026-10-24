import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { MensagemDeErro } from '../../componentes/MensagemDeErro';
import { dataHora, dinheiro } from '../../formato';
import { chamar, chamarComVersao, ErroDaApi } from '../../sessao/api';

type Adiantar = 'NENHUMA' | 'PROXIMA' | 'TODAS';

interface Configuracao {
  provedor: string;
  simulado: boolean;
  pixLigado: boolean;
  boletoLigado: boolean;
  adiantar: Adiantar;
  diasDoBoleto: number;
}

interface ADevolver {
  id: string;
  forma: 'PIX' | 'BOLETO';
  alunoNome: string | null;
  total: number;
  recebido: number;
  recebidoEm: string;
  motivoDaDevolucao: string;
}

const NOME_DO_ADIANTAR: Record<Adiantar, string> = {
  NENHUMA: 'Só as vencidas e as que vencem hoje',
  PROXIMA: 'Também a próxima a vencer',
  TODAS: 'Todas as em aberto',
};

/**
 * Pagamento online (ADMIN; ADR 0034): o que o app oferece ao aluno e o dinheiro que chegou sem
 * ter o que pagar (a devolver pelo painel da empresa). A empresa em si vem do .env.
 */
export function PagamentosOnline() {
  const [atual, setAtual] = useState<Configuracao | null>(null);
  const [versao, setVersao] = useState<string | null>(null);
  const [form, setForm] = useState({ pixLigado: true, boletoLigado: true, adiantar: 'PROXIMA' as Adiantar, diasDoBoleto: '' });
  const [erro, setErro] = useState<unknown>(null);
  const [salvo, setSalvo] = useState(false);
  const [enviando, setEnviando] = useState(false);
  const [aDevolver, setADevolver] = useState<ADevolver[] | null>(null);
  const [erroDaLista, setErroDaLista] = useState<unknown>(null);

  function mostrar(dados: Configuracao, v: string | null) {
    setAtual(dados);
    setVersao(v);
    setForm({ pixLigado: dados.pixLigado, boletoLigado: dados.boletoLigado, adiantar: dados.adiantar, diasDoBoleto: String(dados.diasDoBoleto) });
  }

  const carregarLista = useCallback(() => {
    chamar<ADevolver[]>('/api/pagamentos-online/a-devolver').then(setADevolver).catch(setErroDaLista);
  }, []);

  useEffect(() => {
    chamarComVersao<Configuracao>('/api/pagamentos-online/configuracao').then((r) => mostrar(r.dados, r.versao)).catch(setErro);
    carregarLista();
  }, [carregarLista]);

  async function enviar(evento: FormEvent) {
    evento.preventDefault();
    setErro(null);
    setSalvo(false);
    setEnviando(true);
    try {
      const r = await chamarComVersao<Configuracao>('/api/pagamentos-online/configuracao', { metodo: 'PUT', versao: versao ?? undefined,
        corpo: { pixLigado: form.pixLigado, boletoLigado: form.boletoLigado, adiantar: form.adiantar, diasDoBoleto: Number(form.diasDoBoleto) } });
      mostrar(r.dados, r.versao);
      setSalvo(true);
    } catch (e) {
      setErro(e instanceof ErroDaApi && e.status === 412
        ? new ErroDaApi(412, 'Outra pessoa alterou a configuração. Recarregue a página para ver a atual.') : e);
    } finally {
      setEnviando(false);
    }
  }

  async function devolvido(id: string) {
    setErroDaLista(null);
    try {
      await chamar(`/api/pagamentos-online/${id}/devolvido`, { metodo: 'POST' });
      carregarLista();
    } catch (e) {
      setErroDaLista(e);
    }
  }

  if (!atual) return erro ? <MensagemDeErro erro={erro} /> : <p className="suave" role="status">Carregando…</p>;
  const desligado = atual.provedor === 'desligado';

  return (
    <>
      <h1>Pagamento online</h1>
      <p className="suave">
        {desligado
          ? 'Nenhuma empresa de pagamentos configurada: o app não oferece Pix nem boleto. A empresa é configurada no servidor (.env).'
          : `Empresa de pagamentos: ${atual.provedor}. O pagamento online não passa pelo caixa da recepção.`}
      </p>
      {atual.simulado && <p className="aviso" role="note">Modo de teste: os pagamentos são simulados. Nunca use assim com alunos de verdade.</p>}

      <form className="formulario" onSubmit={enviar} aria-label="Configuração do pagamento online" noValidate>
        <label className="marcar largo"><input type="checkbox" checked={form.pixLigado} onChange={(e) => setForm({ ...form, pixLigado: e.target.checked })} /> Pix pelo app</label>
        <label className="marcar largo"><input type="checkbox" checked={form.boletoLigado} onChange={(e) => setForm({ ...form, boletoLigado: e.target.checked })} /> Boleto pelo app</label>
        <label>O aluno pode pagar
          <select aria-label="Até onde adiantar" value={form.adiantar} onChange={(e) => setForm({ ...form, adiantar: e.target.value as Adiantar })}>
            {Object.entries(NOME_DO_ADIANTAR).map(([valor, nome]) => <option key={valor} value={valor}>{nome}</option>)}
          </select>
        </label>
        <label>Dias de validade do boleto de uma cobrança vencida
          <input type="number" min="1" max="30" step="1" value={form.diasDoBoleto} onChange={(e) => setForm({ ...form, diasDoBoleto: e.target.value })} />
        </label>
        <p className="suave">O boleto tem valor fixo: os juros dos dias de validade não são cobrados.</p>
        <MensagemDeErro erro={erro} rotulos={{ diasDoBoleto: 'Dias do boleto', adiantar: 'Até onde adiantar' }} />
        {salvo && <p className="sucesso" role="status">Configuração salva.</p>}
        <div className="acoes">
          <button type="submit" disabled={enviando || form.diasDoBoleto === ''}>{enviando ? 'Salvando…' : 'Salvar'}</button>
        </div>
      </form>

      <section className="secao" aria-labelledby="titulo-a-devolver">
        <h2 id="titulo-a-devolver">Dinheiro a devolver</h2>
        <p className="suave">
          Chegou pelo app, mas não pagou nada: a cobrança já tinha sido paga (por exemplo, no balcão e pelo app) ou veio outro
          valor. Devolva pelo painel da empresa de pagamentos e marque aqui.
        </p>
        <MensagemDeErro erro={erroDaLista} />
        {aDevolver?.length === 0 && <p>Nada a devolver.</p>}
        <ul className="cartoes">
          {aDevolver?.map((p) => (
            <li key={p.id} className="cartao-item">
              <div className="cabecalho">
                <strong>{p.alunoNome ?? 'Aluno'}</strong>
                <span className="valor">{dinheiro(p.recebido)}</span>
              </div>
              <p>{p.forma === 'PIX' ? 'Pix' : 'Boleto'} recebido em {dataHora(p.recebidoEm)}</p>
              <p className="suave">{p.motivoDaDevolucao}</p>
              <div className="acoes">
                <button type="button" onClick={() => devolvido(p.id)}>Marcar como devolvido</button>
              </div>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}
