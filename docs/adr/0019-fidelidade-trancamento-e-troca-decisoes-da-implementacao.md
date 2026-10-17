# ADR 0019 — Fidelidade, trancamento e troca: decisões tomadas durante a implementação

- **Status:** Aceito (complementa o ADR 0018)
- **Data:** 2026-10-17

## Contexto

O ADR 0018 registrou as regras de negócio antes do código. A implementação (migrations
V13 a V16), os testes de mutação e a validação no Docker trouxeram decisões e defeitos
que ele não previa. Pelo ADR 0001, um ADR aceito não é editado; eles ficam aqui.

## Decisões

### Campos novos do plano são opcionais na API
- `fidelidade`, `percentualMulta` e `formaPagamento` podem faltar no `POST` e no `PUT`:
  ausentes, valem "sem fidelidade" e "mensal". É a mesma regra do `PUT` para os outros
  campos opcionais (ausente = vazio), e não quebra quem já chamava a API.
- O aluno vê a fidelidade e o percentual de multa do plano antes de contratar.

### A troca liga a nova à antiga, e não o contrário
- A nova guarda `troca_de` (único). A antiga é gravada primeiro, encurtada até a
  véspera, para liberar o período; só então a nova é inserida. Com o vínculo na antiga,
  ela apontaria para uma matrícula que ainda não existe.
- A restrição de exclusão passou a usar o período real, `[inicio, trocada_em)` ou
  `[inicio, fim + 1)`. Trocar no próprio dia do início dá um período vazio, que não se
  sobrepõe a nada.
- A coluna `multa_cancelamento` virou `multa`: a troca também gera multa.

### Trocada fica fora das consultas de "período ocupado" e de "impedimento"
- Com o fim original, uma matrícula trocada (que vale só até a véspera) bloquearia a
  renovação da nova e a inativação do aluno depois de cancelar a nova. Como matrículas
  novas nunca começam no passado, excluir as trocadas dessas consultas é exato.

### Trancamento é uma tabela própria; a matrícula guarda o total
- `trancamentos` guarda cada pausa (início, volta prevista, volta real, motivo, quem
  fez); a matrícula guarda `dias_trancados`, e o `CHECK` do fim passou a somá-los.
- A lista de trancamentos vem junto com a matrícula (EAGER): a situação depende dela e
  é calculada no controller, fora da transação (`open-in-view` desligado). São poucos
  por matrícula.
- Trancar e trocar travam o aluno, como matricular: operações que mexem no período
  passam uma de cada vez.

### Cálculo da multa
- Meses restantes = `MONTHS.between(dia + 1, fim + 1)`: contar a partir do dia seguinte
  faz o mês que começa no próprio dia contar como usado, que é a regra do ADR 0018.

## Defeitos encontrados (e por quem)

| Defeito | Quem achou |
|---|---|
| `CHECK (fidelidade AND percentual > 0 ...)` aceitava percentual `NULL`: `NULL > 0` dá `NULL`, e um CHECK que dá `NULL` deixa passar | Teste do banco, na primeira execução |
| A multa contava um mês a mais quando a saída caía no dia em que um mês do período começa | Conferência à mão antes de escrever os testes |
| Um trancamento desfeito (0 dias) ainda contava como "em aberto" e impedia marcar outro | Teste de unidade |
| A trocada, pelo fim original, bloquearia a renovação da nova e a inativação | Revisão das consultas; o primeiro teste não pegava (ia de mensal para anual) e foi refeito |
| Condição redundante na troca (trancada sempre tem trancamento em aberto) | Mutante sobrevivente do PIT |

## Duas camadas na corrida "trancar x renovar"
- Tirar a trava do aluno no trancar não muda o resultado: a restrição de exclusão barra
  a renovação e o `catch` devolve o mesmo 409. Tirando a trava **e** o `catch`, a
  corrida dá 500 já na primeira rodada. A trava dá a mensagem precisa ("renovação
  agendada a partir de..."); o `catch` é a garantia final.

## Consequências

- O `scripts/e2e.sh` passou a cobrir as matrículas (65 verificações numa instalação do
  zero); com a regra da multa na troca quebrada de propósito, ele falha nessa
  verificação.
- A etapa 6 encontra na matrícula: valor, forma de pagamento, dia de vencimento (o do
  início), dias trancados e a multa já calculada.
