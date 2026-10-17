# ADR 0018 — Fidelidade, forma de pagamento, trancamento, troca de plano e vencimento

- **Status:** Aceito (complementa os ADRs 0013 e 0016); complementado pelo [ADR 0019](0019-fidelidade-trancamento-e-troca-decisoes-da-implementacao.md)
- **Data:** 2026-10-17

## Contexto

O ADR 0016 deixou três pendências de negócio: trancamento, troca de plano no meio do
período e dia de vencimento. Ao decidi-las, o dono do projeto trouxe duas regras novas
para os planos: **fidelidade** e **forma de pagamento**, que ele define em cada plano.
As decisões abaixo foram todas tomadas por ele antes da implementação.

## Decisão

### Planos: fidelidade e forma de pagamento (definidas pelo dono)
- O ADMIN (o dono) já cria e edita os planos (ADR 0013). Cada plano passa a dizer:
  - **se tem fidelidade** e, se tiver, o **percentual de multa** por cancelamento
    antecipado: de 0,01% a 100%, com duas casas decimais;
  - a **forma de pagamento**: **mensal** (uma mensalidade por mês) ou **à vista** (uma
    cobrança com o total no início; um desconto, se houver, já vem embutido no valor).
- Plano sem fidelidade não tem percentual. Plano antigo, ou pedido sem esses campos:
  sem fidelidade e mensal.
- Os planos mais usados devem ser o mensal e o anual, mas o dono cria quantos quiser.

### A matrícula copia as regras do plano
- Como o valor (ADR 0013) e o nome (ADR 0017), a matrícula guarda **a fidelidade, o
  percentual de multa e a forma de pagamento do dia**. Mudar o plano depois não muda o
  contrato de quem já está matriculado.

### Cancelamento e multa
- **Sem fidelidade:** cancela quando quiser, sem multa. Paga só até o mês em que cancelou
  (as mensalidades seguintes não são geradas, etapa 6).
- **Com fidelidade:** cancelar antes do fim gera **multa = percentual × saldo restante**.
  - Saldo restante = **meses inteiros que faltam** × valor mensal. O mês em curso conta
    como usado: um anual de 17/10 a 16/10, cancelado em 20/01, tem 8 meses restantes
    (de 17/02 em diante). Dias trancados já entram, porque estendem o fim.
  - Arredondamento para centavos, metade para cima (arredondamento comercial).
  - A multa é calculada e **gravada na matrícula** no cancelamento; cobrá-la é da
    etapa 6.
- Cancelar uma matrícula agendada (que ainda não começou) nunca gera multa. Para os
  meses restantes, conta-se o período inteiro.
- Cancelar uma matrícula trancada pode, com as regras normais (multa, se houver).

### Trancamento
- ADMIN e RECEPCAO, com motivo obrigatório. Só se tranca matrícula **vigente**.
- Início hoje ou futuro, dentro do período; **volta prevista obrigatória**.
- De **7 a 90 dias** por trancamento; no máximo **90 somados** na mesma matrícula. Um
  trancamento por vez.
- **O fim da matrícula avança** pelos dias trancados: o aluno não perde o que pagou.
- **Volta antes do previsto:** a recepção registra a volta, e contam só os dias
  realmente parados (o fim é recalculado). Volta registrada antes de o trancamento
  começar o desfaz (0 dias).
- Durante o trancamento, a situação é **TRANCADA**: o instrutor vê que o aluno não pode
  treinar.
- **Com renovação agendada, o trancamento é recusado (409):** o fim estendido invadiria
  a renovação. A recepção cancela a renovação antes.

### Troca de plano no meio do período
- ADMIN e RECEPCAO. Só de matrícula **vigente** e não trancada, para outro plano ativo.
- **Imediata:** a matrícula atual termina na véspera, com a situação **TROCADA** e o
  vínculo com a nova; a nova começa hoje, com nome, preço e regras do novo plano (e,
  se tiver fidelidade, ela começa a contar na troca).
- **Multa na troca** (só se a atual tiver fidelidade): **não há multa** se o novo plano
  também tiver fidelidade **e** valor mensal igual ou maior. Nos outros casos, a troca
  aplica a multa da atual, como num cancelamento. Sem essa regra, trocar para um plano
  mensal sem fidelidade (que costuma ter valor mensal maior que o anual) e cancelar em
  seguida anularia a fidelidade.
- Crédito ou diferença proporcional entre os planos fica para a etapa 6.
- **Com renovação agendada ou com a matrícula trancada, a troca é recusada (409)** e
  a mensagem diz o que fazer (cancelar a renovação; registrar a volta). Nada de efeito
  colateral em algo que envolve dinheiro.

### Dia de vencimento (implementado na etapa 6)
- **O dia do início da matrícula:** matrícula do dia 17 vence todo dia 17. Sem esse dia
  no mês (29 a 31), vence no último dia dele.
- Pagamento **mensal**: uma mensalidade por mês do período. **À vista**: uma cobrança
  com o valor total, vencendo no início.

## Mudanças em relação ao ADR 0016

- **O fim deixa de ser fixo:** passa a ser início + N meses − 1 dia **+ dias
  trancados**. O `CHECK` do banco acompanha.
- **Situações novas:** TRANCADA (calculada pelas datas do trancamento) e TROCADA
  (gravada na troca).
- **Período ocupado** passa a considerar o fim real: a matrícula trocada ocupa só até a
  véspera da troca. A restrição de exclusão do banco usa esse fim.

## Alternativas consideradas

- **Multa fixa em reais:** não acompanha o tamanho do compromisso restante.
- **Trancamento que empurra a renovação agendada:** mudaria sozinho as datas de outra
  matrícula, que a recepção pode já ter combinado com o aluno.
- **Troca sem multa para qualquer plano mais caro:** abre a brecha descrita acima.

## Consequências

- O percentual de multa é decisão do dono, plano a plano; **vale conferir com um
  advogado** o percentual usado, porque multa abusiva pode ser contestada (CDC).
- A etapa 6 recebe da matrícula tudo de que precisa: valor, forma de pagamento, dia de
  vencimento (o do início) e a multa já calculada.
