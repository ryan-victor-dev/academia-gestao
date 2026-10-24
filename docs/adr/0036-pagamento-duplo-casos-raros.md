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

## Consequências

- Uma resposta perdida na rede (a empresa devolveu, mas o sistema não soube) volta para
  `A_DEVOLVER`, e "por fora" fica liberado. A tela pede para tentar de novo pelo sistema antes:
  com a mesma chave, a empresa responde que já devolveu, e nada sai duas vezes.
- Teste que insere pagamento online à mão em `DEVOLVENDO` precisa do recebido, do motivo e do
  valor a devolver, como em `A_DEVOLVER`.
