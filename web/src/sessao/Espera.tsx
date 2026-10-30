import { useEffect, useState } from 'react';
import { ErroDaApi } from './api';

/** "45 s", "4 min 32 s", "2 h 10 min": o tempo que falta, como se fala. */
export function formatarEspera(segundos: number): string {
  // Espaço que não quebra entre o número e a unidade: "44" numa linha e "s" na outra não se lê.
  const de = (numero: number, unidade: string) => `${numero}\u00a0${unidade}`;
  const horas = Math.floor(segundos / 3600);
  const minutos = Math.floor((segundos % 3600) / 60);
  const resto = segundos % 60;
  if (horas > 0) return minutos > 0 ? `${de(horas, 'h')} ${de(minutos, 'min')}` : de(horas, 'h');
  if (minutos > 0) return resto > 0 ? `${de(minutos, 'min')} ${de(resto, 's')}` : de(minutos, 'min');
  return de(resto, 's');
}

/**
 * A espera de um 429 (muitas tentativas, ADR 0022): a API diz no Retry-After quantos segundos
 * faltam, e a tela conta até zero. A espera é de uma conta (o e-mail digitado): outra pessoa, no
 * mesmo computador da recepção, não espera pela primeira. Se o limite for o do IP, a API recusa de
 * novo e a contagem recomeça com o tempo certo.
 */
export function useEspera() {
  const [fim, setFim] = useState<{ chave: string; ate: number; titulo: string } | null>(null);
  const [agora, setAgora] = useState(() => Date.now());

  useEffect(() => {
    if (fim === null) return;
    const relogio = setInterval(() => {
      const instante = Date.now();
      setAgora(instante);
      if (instante >= fim.ate) setFim(null);
    }, 1000);
    return () => clearInterval(relogio);
  }, [fim]);

  return {
    /** Começa a contar, se o erro for um 429 com o tempo. Devolve se começou. */
    aguardar(erro: unknown, chave: string): boolean {
      if (!(erro instanceof ErroDaApi) || erro.status !== 429 || erro.esperar === null) return false;
      const instante = Date.now();
      setAgora(instante);
      setFim({ chave, ate: instante + erro.esperar * 1000, titulo: erro.titulo ?? 'Muitas tentativas' });
      return true;
    },
    /** Os segundos que faltam para esta conta (0: pode tentar), e o título do aviso. */
    de(chave: string): { restante: number; titulo: string } {
      const restante = fim !== null && fim.chave === chave ? Math.max(0, Math.ceil((fim.ate - agora) / 1000)) : 0;
      return { restante, titulo: fim?.titulo ?? '' };
    },
  };
}

/** O aviso com a contagem. O alerta é lido uma vez; a contagem muda sem ser lida a cada segundo. */
export function AvisoDeEspera({ restante, titulo }: { restante: number; titulo: string }) {
  if (restante <= 0) return null;
  return (
    <p className="erro" role="alert">
      {titulo}. Tente de novo em <span aria-live="off">{formatarEspera(restante)}</span>.
    </p>
  );
}
