import { render, screen } from '@testing-library/react';
import qrcode from 'qrcode-generator';
import { describe, expect, it } from 'vitest';
import { desenhoDoQr, QrCode } from './QrCode';

// Um copia e cola no formato do Pix (BR Code), do tamanho dos de verdade.
const PIX = '00020101021226830014br.gov.bcb.pix2561pix.exemplo.com.br/qr/v2/cobv/9d36b84f-c70b-478f-b95c-12729b90ca2552040000'
  + '530398654061021.105802BR5913Academia Teste6009SAO PAULO62070503***6304ABCD';

/** Os módulos escuros que o caminho desenha, de volta em "linha,coluna" (sem a margem). */
function modulosDoCaminho(caminho: string): Set<string> {
  return new Set([...caminho.matchAll(/M(\d+) (\d+)h1v1h-1z/g)].map(([, x, y]) => `${Number(y) - 4},${Number(x) - 4}`));
}

describe('QR code', () => {
  // Correção M: com outro nível, os módulos mudam, e a comparação abaixo falha.
  it('desenha exatamente os módulos escuros do QR do texto, com 4 de margem', () => {
    const qr = qrcode(0, 'M');
    qr.addData(PIX);
    qr.make();
    const esperados = new Set<string>();
    for (let l = 0; l < qr.getModuleCount(); l++) {
      for (let c = 0; c < qr.getModuleCount(); c++) if (qr.isDark(l, c)) esperados.add(`${l},${c}`);
    }

    const { lado, caminho } = desenhoDoQr(PIX);

    expect(lado).toBe(qr.getModuleCount() + 8);
    expect(modulosDoCaminho(caminho)).toEqual(esperados);
  });

  it('preto no branco, com o nome para o leitor de tela', () => {
    render(<QrCode texto="PIX-TESTE" rotulo="QR code do Pix" />);
    const svg = screen.getByRole('img', { name: 'QR code do Pix' });
    const { lado } = desenhoDoQr('PIX-TESTE');
    expect(svg.getAttribute('viewBox')).toBe(`0 0 ${lado} ${lado}`);
    expect(svg.querySelector('rect')?.getAttribute('fill')).toBe('#fff');
    expect(svg.querySelector('path')?.getAttribute('fill')).toBe('#000');
    expect(svg.querySelector('path')?.getAttribute('d')).toBe(desenhoDoQr('PIX-TESTE').caminho);
  });
});
