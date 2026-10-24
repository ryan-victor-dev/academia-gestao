# ADR 0036 — Pagamento duplo: os casos raros que sobraram

- **Status:** Aceito (completa o ADR 0035)
- **Data:** 2026-10-24

## Contexto

Depois do ADR 0035, o caso principal de pagamento duplo (dois Pix para a mesma cobrança) estava
resolvido, e o dinheiro pago duas vezes (Pix e boleto, app e balcão) ia para "a devolver". O dono
pediu (2026-10-24) para fechar os casos raros que o próprio ADR 0035 deixou registrados:

1. **Devolução em dobro.** O ADMIN pede "Devolver" numa aba e marca "Já devolvi por fora" em
   outra, ao mesmo tempo: o dinheiro voltava duas vezes.
2. **Pix órfão.** Na corrida de dois pedidos (ADR 0035), o perdedor era cancelado na empresa e
   esquecido aqui. Se o cancelamento falhasse e alguém pagasse aquele Pix, o dinheiro chegava, e
   o sistema não o reconhecia (o aviso virava "id desconhecido" no log).
3. **Pago no último segundo, sem aviso.** Um Pix trocado (cancelado) ou expirado, mas pago na
   virada, ou cujo cancelamento na empresa falhou, só era descoberto pelo aviso da empresa. E o
   aviso só chega com o site na internet: no servidor sem endereço público, o dinheiro chegava
   e ninguém percebia.

## Decisões

### DEVOLVENDO antes de chamar a empresa (decisão técnica)
- Situação nova `DEVOLVENDO` (V45). A devolução pela empresa trava a linha, passa de
  `A_DEVOLVER` para `DEVOLVENDO` e só então chama a empresa (fora de transação, ADR 0034).
  Enquanto está `DEVOLVENDO`, "devolvido por fora" é recusado (409).
- A empresa recusou ou não respondeu: volta a `A_DEVOLVER`, e o ADMIN tenta de novo ou devolve
  por fora. Deu certo: `DEVOLVIDO`, com a trilha `PELA_EMPRESA`.
- O sistema caiu no meio: fica `DEVOLVENDO`. A lista do ADMIN mostra "em andamento" e deixa
  tentar de novo, que é seguro: a chave da devolução é a mesma, e a empresa não devolve duas
  vezes (ADR 0035). "Por fora" continua recusado até a devolução terminar.
- Alternativa descartada: travar a linha durante a chamada à empresa. Seguraria uma conexão do
  banco enquanto a rede responde (ADRs 0020 e 0034).

### O perdedor da corrida fica gravado como CANCELADO (decisão técnica)
- Quem perde a corrida cancela o seu na empresa e grava a linha como `CANCELADO` (o índice único
  só vale para os `AGUARDANDO`, ADR 0035). Cancelado ainda recebe: se o cancelamento lá falhou e
  alguém pagar, o aviso e a consulta acham o pagamento, e o dinheiro paga a cobrança ou fica a
  devolver, como qualquer outro.
- Se nem essa gravação der certo (banco fora do ar), o id da empresa vai para o log como "ficou
  fora do sistema", para conferir no painel dela.

### Consulta dos encerrados, de hora em hora (decisão técnica)
- Além da consulta dos em aberto (a cada 2 minutos, ADR 0034), uma segunda consulta, **a cada
  hora**, pergunta à empresa pelos `CANCELADO` e `EXPIRADO` cujo prazo acabou nos **últimos 7
  dias** (`intervalo-da-consulta-dos-encerrados` e `janela-dos-encerrados`). Pago: paga a
  cobrança ou fica a devolver, pelo mesmo caminho do aviso.
- A empresa diz que ainda está em aberto um que aqui está encerrado (o cancelamento falhou): o
  sistema cancela de novo lá.
- Por que de hora em hora, e não junto com os em aberto: cada encerrado seria consultado 30
  vezes por hora durante 7 dias, e as empresas limitam as chamadas.
- Índice parcial novo (V46) para essa busca.

## Consequências

- Uma resposta perdida na rede (a empresa devolveu, mas o sistema não soube) volta para
  `A_DEVOLVER`, e "por fora" fica liberado. A tela pede para tentar de novo pelo sistema antes:
  com a mesma chave, a empresa responde que já devolveu, e nada sai duas vezes.
- Um encerrado pago depois da janela de 7 dias só aparece pelo aviso da empresa, ou no painel dela.
- Teste que insere pagamento online à mão em `DEVOLVENDO` precisa do recebido, do motivo e do
  valor a devolver, como em `A_DEVOLVER`.
