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
3. **A devolução era só manual.** O dinheiro que chegava sem ter o que pagar ia para uma lista;
   o ADMIN devolvia pelo painel da empresa e marcava. E qualquer valor diferente do combinado
   devolvia tudo, mesmo quando o aluno pagava a mais.

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

### Devolução pelo sistema, com o ADMIN confirmando (decisão do dono)
- O dono escolheu (2026-10-24) que **o ADMIN confirma** cada devolução: na lista "Dinheiro a
  devolver", o botão "Devolver" faz o sistema pedir a devolução à empresa. Nenhum dinheiro sai
  sem alguém olhar. A alternativa, devolver sozinho assim que percebe, foi descartada pelo dono.
- `ProvedorDePagamento` ganha `devolve(forma)` e `devolver(devolucao)`. A devolução leva uma
  **chave** (o id do pagamento aqui), e a empresa precisa garantir que a mesma chave não devolve
  duas vezes (a devolução do Pix faz isso pelo id que quem pede escolhe). Assim, um clique duplo
  ou uma resposta perdida na rede podem ser repetidos sem risco. A chamada fica fora de transação
  (ADR 0034); depois, a linha é travada e só um pedido registra.
- **Boleto**: a maioria das empresas não devolve boleto pelo sistema (só por transferência, com
  os dados do banco do aluno). O simulado faz o mesmo: Pix sim, boleto não. Quando a empresa não
  devolve, ou o pagamento veio de outra empresa (a academia trocou), continua o caminho manual:
  devolver por fora e marcar como devolvido. A trilha guarda como foi (`PELA_EMPRESA` ou `POR_FORA`).
- **Pago a mais** (decisão do dono): a cobrança fica paga com o combinado, e só a sobra volta. O
  lançamento da cobrança é o combinado (a sobra não é da academia). Pago a menos continua
  devolvendo tudo, e a cobrança fica em aberto. Coluna nova `valor_a_devolver` (V44): o que
  estava a devolver antes dela era tudo o que chegou.

## Consequências

- Na corrida, a empresa recebe um pedido a mais, cancelado logo em seguida. Se o cancelamento
  falhasse e alguém pagasse aquele Pix, o sistema não o reconhecia. Resolvido no ADR 0036: o
  perdedor fica gravado como cancelado.
- Teste que insere pagamentos online à mão: só um `AGUARDANDO` por cobrança e forma.
- Uma empresa de verdade só serve se tiver devolução com chave (idempotente). Sem isso, a classe
  dela deve responder `devolve = false`, e a devolução fica manual.
- Se o ADMIN marcasse "devolvido por fora" numa aba enquanto pedia a devolução pela empresa em
  outra, o dinheiro podia voltar duas vezes. Resolvido no ADR 0036 (`DEVOLVENDO`).
- A devolução do Pix tem prazo no arranjo (90 dias): passado isso, a empresa recusa, e o ADMIN
  devolve por transferência.
