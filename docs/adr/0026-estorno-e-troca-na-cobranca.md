# ADR 0026 — Estorno e troca de plano na cobrança

- **Status:** Aceito (completa os ADRs 0024 e 0025)
- **Data:** 2026-10-21

## Contexto

O diagnóstico da etapa 6 (cobrança), em 2026-10-20, achou quatro regras que o ADR 0024
não tinha decidido, todas reproduzidas no Docker e levadas ao dono:

1. **O estorno cobrava juros desde o vencimento.** Um estorno feito meses depois (uma
   contestação do cartão) somava acréscimo e juros de todo o período e bloqueava o aluno na
   hora, embora até ali a academia desse a cobrança por paga.
2. **O estorno de uma parcela que saiu do cronograma voltava a dever.** Uma parcela paga
   adiantada continua paga quando a matrícula é cancelada; estornada depois, voltava a ficar
   em aberto e, vencida, bloqueava o aluno por um mês que ele não ia usar.
3. **A troca no meio do mês cobrava o mesmo período duas vezes.** A matrícula nova cobrava a
   primeira parcela no dia da troca, mas o mês em curso da antiga já tinha sido cobrado
   inteiro.
4. **O à vista trocado perdia o saldo.** Quem pagou 12 meses à vista e trocou de plano no
   3º continuava com a à vista paga, e a nova cobrava tudo de novo. A regra "à vista
   cancelado: sem devolução" foi decidida para o cancelamento, não para a troca. O mesmo
   valia para a mensalidade paga adiantada de um mês que começa depois da troca.

## Decisões (todas do dono)

### Depois de um estorno, o atraso conta do dia do estorno
- A cobrança guarda o dia do último estorno. O **prazo** é o vencimento ou, se for depois
  dele, o dia do estorno; acréscimo, juros, situação, lista de atrasadas e o "pode
  treinar?" contam do prazo.
- No banco, o prazo é uma coluna gerada (V25), com o índice das pendentes. A V25 preencheu o
  dia dos estornos já feitos, pela trilha.

### Estorno de parcela fora do cronograma a cancela
- O acerto do cronograma marca a paga que deixou de ser prevista (`fora_do_cronograma`). O
  estorno dela vai para o registro de lançamentos como sempre, e a cobrança fica
  **CANCELADA** por quem estornou, em vez de voltar a dever.
- O banco garante que nenhuma marcada fique em aberto (V26).

### Na troca, a nova cobra a partir do próximo vencimento da antiga
- A matrícula nova começa no dia da troca, mas as parcelas e os meses do plano contam do
  **próximo vencimento** da antiga: o primeiro mês dela que começa no dia ou depois. Na
  troca no último mês, é o dia seguinte ao fim. O fim vai junto: o aluno tem os meses
  inteiros do plano novo, mais os dias que já tinha pago.
- A matrícula guarda o `primeiro_vencimento` (V27; vazio = o início), e a resposta o
  mostra. O banco confere que ele só existe na matrícula de uma troca, até 1 mês depois do
  início, e que o fim conta dele.

### O que foi cobrado e não usado vira crédito na nova
- Crédito = o que foi cobrado da antiga (as mensalidades e a à vista que ficam com ela, e
  as pagas adiantadas que saem dela; não a multa nem as canceladas) + o crédito que ela já
  tinha − valor mensal × meses usados. Nunca negativo. A à vista de 12 × R$ 100 trocada no
  3º mês deixa R$ 1.000.
- Só o **cobrado** conta, pago ou não: a em aberto continua devida na antiga. Contar o
  plano inteiro daria crédito a quem nunca pagou, e a troca no dia do início (quando a à
  vista é cancelada) daria crédito sem cobrança nenhuma.
- A paga adiantada que sai da antiga na troca **não** fica marcada fora do cronograma: ela
  virou crédito, então o estorno a faz voltar a dever (no cancelamento, ele a cancela). A
  trocada não aceita mais operações, e o cronograma dela não é recalculado depois disso.
- O crédito abate as primeiras parcelas da nova, em ordem; a que fica zerada não é criada.
  Numa nova à vista, abate o total. Trocada de novo, a nova leva o que sobrou.
- **Crédito maior que o plano novo inteiro: a troca é recusada** (409, com os dois
  valores). Nada se perde sem alguém ver; a academia escolhe um plano que o cubra ou
  resolve por fora. Igual ao plano inteiro, o plano novo sai pago.
- A matrícula guarda o `credito` (V28; vazio = sem crédito), e a resposta o mostra. O banco
  confere que ele só existe na matrícula de uma troca e fica entre zero e o plano inteiro.

## Alternativas descartadas
- **Recusar o estorno de parcela fora do cronograma:** o dinheiro já voltou ao aluno (a
  contestação do cartão não pede licença); recusar só deixaria o registro errado.
- **Cobrar a nova desde o dia da troca, proporcional aos dias:** mudaria o dia de
  vencimento do aluno e criaria parcelas de valores quebrados.
- **Reduzir a à vista em aberto em vez de dar crédito:** exigiria mudar o valor de uma
  cobrança já emitida; com o crédito, nenhuma cobrança muda de valor.

## Consequências
- Uma à vista em aberto trocada gera crédito e continua devida: se o aluno não pagar, ele é
  bloqueado pelo atraso dela, e o crédito não some.
- A conta do crédito trava as cobranças da antiga: um cancelamento ou pagamento ao mesmo
  tempo espera a troca terminar.
