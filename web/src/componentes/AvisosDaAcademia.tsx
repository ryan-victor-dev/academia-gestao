import { useEffect, useState } from 'react';
import { chamar } from '../sessao/api';
import { MensagemDeErro } from './MensagemDeErro';

/**
 * Ligar ou desligar os avisos da academia (eventos e comunicados por e-mail e SMS, ADR 0047). Os lembretes de fatura
 * não se desligam.
 */
export function AvisosDaAcademia({ somenteLeitura = false }: { somenteLeitura?: boolean }) {
  const [recebe, setRecebe] = useState<boolean | null>(null);
  const [salvo, setSalvo] = useState(false);
  const [erro, setErro] = useState<unknown>(null);

  useEffect(() => {
    let atual = true;
    chamar<{ recebeAvisosDaAcademia: boolean }>('/api/avisos/preferencias')
      .then((p) => { if (atual) setRecebe(p.recebeAvisosDaAcademia); }).catch((e) => { if (atual) setErro(e); });
    return () => { atual = false; };
  }, []);

  // A caixa muda na hora (quem clica vê o efeito) e volta se a API recusar.
  async function mudar(novo: boolean) {
    const antes = recebe;
    setErro(null);
    setSalvo(false);
    setRecebe(novo);
    try {
      const p = await chamar<{ recebeAvisosDaAcademia: boolean }>('/api/avisos/preferencias',
        { metodo: 'PUT', corpo: { recebeAvisosDaAcademia: novo } });
      setRecebe(p.recebeAvisosDaAcademia);
      setSalvo(true);
    } catch (e) {
      setRecebe(antes);
      setErro(e);
    }
  }

  return (
    <section>
      <h2>Avisos da academia</h2>
      <MensagemDeErro erro={erro} />
      {recebe !== null && (
        <label className="marcar">
          <input type="checkbox" checked={recebe} disabled={somenteLeitura} onChange={(e) => mudar(e.target.checked)} />
          Receber por e-mail e SMS os eventos e comunicados da academia
        </label>
      )}
      {salvo && <p role="status">{recebe ? 'Você vai receber os avisos.' : 'Pronto: você não vai mais receber esses avisos.'}</p>}
      <p className="suave">Os lembretes de fatura (antes do vencimento e em atraso) continuam chegando.</p>
    </section>
  );
}
