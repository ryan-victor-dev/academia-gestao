# ADR 0030 — Arquitetura das matrículas: fronteiras, regras do plano num lugar só e entidade menor

- **Status:** Aceito (completa os ADRs 0002 e 0015; R4 e R5 ficaram no ADR 0023)
- **Data:** 2026-10-22

## Contexto

A revisão geral de 2026-10-17 apontou seis pontos de arquitetura (R1–R6), sem gravidade
de segurança, mas de manutenção. R4 (relógio único) e R5 (limpeza central dos testes)
foram resolvidos no ADR 0023. Os outros quatro:

- **R1:** a `Matricula` tinha 482 linhas e cuidava de período, situação, trancamento,
  cancelamento, multa, troca e da tradução para a cobrança: começo de "entidade-deus".
- **R2:** a entidade `Matricula` usava `planos.FormaPagamento`, e nenhuma regra do ArchUnit
  cobria isso. A regra nova achou mais: desde a cobrança (etapa 6), ela também montava e
  lia os tipos da porta da cobrança.
- **R3:** o plano e a matrícula tinham cada um a sua cópia das regras das condições do plano
  e dos limites, e elas já divergiam (a limpeza do nome e as mensagens).
- **R6:** os trancamentos vinham junto com cada matrícula, com uma consulta por matrícula
  numa lista (N+1).

## Decisões

### A entidade só depende do próprio módulo e do compartilhado (R2)
- Regra nova no `ArquiteturaTest`: classe com `@Entity` não depende de outro módulo (só do
  próprio, do `compartilhado`, do Java e das anotações de persistência).
- O que a matrícula copia de outro módulo vira um tipo dela: `FormaDePagamento`, convertida
  do plano por um `switch` sem default (uma forma nova no plano não compila até alguém
  decidir o que ela é na matrícula). Os nomes são os mesmos: nada muda no banco nem na API.
- A tradução para a cobrança sai da entidade para `MatriculaNaCobranca`.

### As condições do plano conferidas num lugar só (R3)
- `compartilhado.CondicoesDoPlano` confere e normaliza nome, valor mensal, duração,
  fidelidade e multa; o plano e a matrícula usam a mesma. Fica no compartilhado porque é o
  contrato entre os dois módulos (ADR 0002: "o que é realmente comum") e porque a entidade
  da matrícula não depende do módulo de planos (R2).
- Os limites ficam só ali, inclusive para as anotações da requisição do plano (os tetos
  também em texto, como o `@DecimalMax` exige). Os `CHECK`s do banco (V23) continuam como
  última barreira, de propósito: valem até para quem grava sem passar pelo Java.
- A matrícula passa a limpar o nome do plano como o plano e a usar as mesmas mensagens.

### Trancamentos em lote (R6)
- `@BatchSize(size = 50)` nos trancamentos: numa lista, os de até 50 matrículas vêm numa
  consulta só. `SUBSELECT` não: ignora a paginação (ADR 0028).

### A matrícula delega as contas (R1)
- `RegrasDoTrancamento` (as datas de um trancamento novo: começo, 7 a 90 dias, 90 somados)
  e `MultaDeFidelidade` (a conta da multa e a comparação da troca) saíram da entidade, em
  Java puro. A `Matricula` continua decidindo **quando** (situação, trancamento em aberto,
  cancelamento, troca) e guarda o resultado.
- O período (início, fim, dias trancados) ficou na entidade: são poucas linhas, e um tipo
  embutido mudaria o mapeamento sem ganho.

## Alternativas descartadas
- **`FormaPagamento` no compartilhado:** é do plano; a matrícula guarda uma cópia, e o
  `switch` sem default obriga a decidir, em vez de herdar sem ninguém ver.
- **A matrícula sem conferir o que copia** (só o plano confere): a entidade valida tudo
  antes de mudar (CLAUDE.md), e o plano pode mudar de regra depois de a matrícula existir.
- **Dividir a matrícula em várias entidades** (trancamentos, cancelamento, troca): mexeria
  nas tabelas, nas travas e na cobrança, por um ganho que as duas classes de regras já dão.

## Consequências
- A `Matricula` foi de 482 para 402 linhas; o resto são campos, getters e as
  transições de estado, que dependem umas das outras.
- Regra nova de "o que é comum" entre planos e matrículas vai para `CondicoesDoPlano`.

## Verificação
- Antes da correção, a regra do ArchUnit apontou 16 violações; o teste novo do R6 contou 6
  consultas para 5 matrículas (agora 2).
- Mutações à mão, com o build limpo a cada mutante: R2 10, R3 17, R6 2 (sem o lote e o lote
  menor que a lista), R1 15. Ver os commits.
- e2e numa instalação do zero, a cada parte: 118 ok.
