import qrcode from 'qrcode-generator';

// A margem branca em volta, em módulos: os leitores pedem 4.
const MARGEM = 4;

/**
 * O desenho do QR code: um quadrado de 1×1 por módulo escuro, num só caminho do SVG. Nível de
 * correção M, o usual no Pix. O QR do Pix é o próprio copia e cola (ADR 0035): a tela desenha, e
 * a empresa de pagamentos não precisa mandar imagem nenhuma.
 */
export function desenhoDoQr(texto: string): { lado: number; caminho: string } {
  const qr = qrcode(0, 'M');
  qr.addData(texto);
  qr.make();
  const modulos = qr.getModuleCount();
  let caminho = '';
  for (let linha = 0; linha < modulos; linha++) {
    for (let coluna = 0; coluna < modulos; coluna++) {
      if (qr.isDark(linha, coluna)) caminho += `M${coluna + MARGEM} ${linha + MARGEM}h1v1h-1z`;
    }
  }
  return { lado: modulos + 2 * MARGEM, caminho };
}

/** Preto no branco sempre (também no tema escuro): é o que os leitores de QR esperam. */
export function QrCode({ texto, rotulo }: { texto: string; rotulo: string }) {
  const { lado, caminho } = desenhoDoQr(texto);
  return (
    <svg className="qr-code" role="img" aria-label={rotulo} viewBox={`0 0 ${lado} ${lado}`} shapeRendering="crispEdges">
      <rect width={lado} height={lado} fill="#fff" />
      <path d={caminho} fill="#000" />
    </svg>
  );
}
