# ADR 0035 — Pendências do pagamento online: um Pix por vez, QR code e devolução pelo sistema

- **Status:** Aceito (atualiza o ADR 0034)
- **Data:** 2026-10-24

## Contexto

O pagamento online (ADR 0034) ficou com pendências conhecidas, e o dono pediu (2026-10-24) para
resolvê-las, menos a ligação com uma empresa de verdade, que fica para depois:

1. **Dois pedidos ao mesmo tempo geravam dois Pix.** Duas abas, ou dois toques no botão: os dois
   pedidos viam que não havia Pix valendo e geravam um cada. Os dois podiam ser pagos, e o
   segundo ficava a devolver.
2. **O Pix não tinha QR code**, só o copia e cola. Quem abre o app no computador (ou no celular
   de outra pessoa) precisa de um QR para ler com o app do banco.

## Decisões

### Um Pix e um boleto aguardando por cobrança (decisão técnica)
- Índice único parcial no banco (V43): `(cobranca_id, forma)` entre os `AGUARDANDO`. A chamada à
  empresa continua fora de transação (ADR 0034), então os dois pedidos ainda geram lá; quem grava
  em segundo esbarra no índice, **cancela o seu na empresa** e devolve ao aluno o que ficou. O
  aluno vê um Pix só, e nenhum pedido dá erro.
- Os duplicados de antes da V43 viram `CANCELADO` (fica o mais novo). Cancelado ainda recebe: se
  alguém pagar, o dinheiro paga a cobrança ou fica a devolver, como antes.
- Alternativa descartada: travar a cobrança durante a chamada à empresa. Seguraria uma conexão do
  banco enquanto a rede responde, o que o ADR 0034 evita (ADR 0020: o pool esgota sob carga).

### QR code desenhado na tela (decisão técnica)
- O QR do Pix é o próprio copia e cola (o "BR Code" do Banco Central): a tela desenha o QR a
  partir dele, e a empresa de pagamentos não precisa mandar imagem. Serve para qualquer empresa.
- Biblioteca `qrcode-generator` (MIT, sem dependências), desenhada em SVG direto na página (sem
  imagem nem script de fora, então a CSP não muda), com correção M e 4 módulos de margem; preto no branco
  também no tema escuro, que é o que os leitores esperam. O boleto não tem QR.

## Consequências

- Na corrida, a empresa recebe um pedido a mais, cancelado logo em seguida. Se o cancelamento
  falhar (empresa fora do ar), fica um Pix na empresa que o sistema conhece só pelo id externo
  dela: se for pago, a consulta não o acha e o aviso é registrado no log como id desconhecido.
  Com o simulado, isso não acontece; com uma empresa de verdade, o cancelamento que falhou fica
  no log para conferir no painel dela.
- Teste que insere pagamentos online à mão: só um `AGUARDANDO` por cobrança e forma.
