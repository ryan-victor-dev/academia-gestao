# ADR 0016 — Matrículas: preço congelado, períodos sem sobreposição e quem vê o quê

- **Status:** Aceito; complementado pelo [ADR 0017](0017-matriculas-decisoes-da-implementacao.md)
- **Data:** 2026-10-17

## Contexto

A matrícula liga um aluno a um plano por um período. É a base da cobrança
(etapa 6): a `Mensalidade` já nasce apontando para uma matrícula. O ADR 0013
deixou um compromisso: **um reajuste de plano vale só para matrículas novas**,
então cada matrícula precisa guardar o preço do dia em que foi feita.

As regras de negócio abaixo foram decididas com o dono do projeto antes da
implementação.

## Decisão

### Preço e duração congelados
- Ao ser criada, a matrícula **copia** o `valor_mensal` e a `duracao_meses` do
  plano. Reajustar ou mudar a duração do plano depois não afeta quem já está
  matriculado. A matrícula guarda o id do plano só para saber de onde veio.
- O valor é sempre o do plano: **não há desconto** na matrícula. Promoção ou
  bolsa vira um plano próprio, com preço visível e auditável.

### Período
- O início é **hoje ou uma data futura** (matrícula agendada); data no passado
  é recusada (400). Sem data informada, vale hoje.
- "Hoje" é a data no fuso da academia (`America/Sao_Paulo`), não em UTC: às
  22h de São Paulo já é o dia seguinte em UTC.
- O fim é **início + N meses − 1 dia**, inclusivo: um plano mensal de 17/10
  vai até 16/11, e a renovação começa em 17/11. Quando o mês seguinte não tem o
  dia (início em 31/01), vale o último dia dele menos um (fim em 27/02,
  renovação em 28/02): os períodos continuam encadeados, sem buraco e sem
  sobreposição.

### Um aluno, um período por vez
- Os períodos das matrículas não canceladas de um aluno **não se sobrepõem**.
  Na prática, ele tem no máximo uma matrícula valendo, e pode ter a renovação
  agendada para começar no dia seguinte ao fim da atual.
- A regra mora no banco: uma restrição de exclusão
  (`EXCLUDE USING gist (aluno_id WITH =, daterange(inicio, fim, '[]') WITH &&)
  WHERE (cancelada_em IS NULL)`), que exige a extensão `btree_gist` (instalada
  pela migration, com o dono do banco, como o `unaccent`). Dois cadastros
  simultâneos passariam pela checagem no Java; o banco barra o segundo, e o erro
  também vira 409.
- Uma matrícula cancelada sai da restrição: o aluno pode ser matriculado de
  novo logo em seguida.

### Situação calculada, não gravada
- A situação é derivada das datas: **AGENDADA** (início no futuro),
  **VIGENTE** (hoje entre início e fim), **ENCERRADA** (fim no passado) ou
  **CANCELADA** (a única gravada, pelo cancelamento).
- Não há tarefa agendada para "virar" matrículas de um estado para outro: nada
  fica desatualizado se o servidor ficar fora do ar à meia-noite.
- **Renovar é criar uma matrícula nova**, que pega o preço vigente do plano.
  Não há renovação automática.

### Cancelamento
- ADMIN ou RECEPCAO, com **motivo obrigatório**. Fica gravado quem cancelou,
  quando e o motivo; a matrícula nunca é apagada.
- Só se cancela matrícula AGENDADA ou VIGENTE (409 para encerrada ou já
  cancelada). Uma vigente termina na data do cancelamento; uma agendada fica
  cancelada sem ter começado.
- Multa, estorno e mensalidades em aberto são da cobrança (etapa 6).

### Pré-condições
- Aluno e plano precisam estar **ativos** para matricular (409). Um plano
  inativado depois não afeta as matrículas existentes (ADR 0013).
- **Inativar um aluno com matrícula vigente ou agendada é recusado (409):**
  cancele antes. Cancelar envolve dinheiro e motivo, e não deve acontecer como
  efeito colateral de outra operação.
- Corrida entre inativar o aluno e matriculá-lo: as duas operações travam a
  linha do aluno (`SELECT ... FOR UPDATE`) antes de conferir, então uma espera a
  outra e confere de novo.

### Fronteiras entre módulos
- `matriculas` depende de `alunos` e de `planos` por portas públicas (aluno
  ativo, dados do plano). A checagem na inativação não pode fazer `alunos`
  depender de `matriculas`, porque isso criaria um ciclo. Por isso, `alunos`
  declara uma interface ("há algo que impede inativar este aluno?") e
  `matriculas` a implementa: a dependência continua numa direção só (inversão de
  dependência), e o `ArquiteturaTest` passa a listar as portas novas.

### Quem pode o quê

| Operação | ADMIN | RECEPCAO | INSTRUTOR | ALUNO |
|---|:-:|:-:|:-:|:-:|
| Matricular / cancelar | ✓ | ✓ | | |
| Listar / consultar | ✓ | ✓ | só se está vigente, sem valores | |
| Ver as próprias (`/api/matriculas/me`) | | | | ✓ |

- O instrutor só precisa saber se o aluno está com a matrícula em dia para
  treinar; preço e histórico não são da conta dele (LGPD, minimização, como no
  ADR 0009).
- O aluno vê as próprias matrículas pelo id da claim `alunoId` do token, nunca
  pela URL (sem IDOR, ADR 0008).

## Alternativas consideradas

- **Ler o preço do plano na hora de cobrar:** um reajuste mudaria o que todos
  pagam, sem aviso; quebra o compromisso do ADR 0013.
- **Situação gravada e atualizada por tarefa noturna:** mais simples de
  consultar, mas fica errada se a tarefa falhar ou atrasar.
- **Índice único parcial "uma matrícula ativa por aluno":** não permite agendar
  a renovação antes de a atual acabar. A restrição de exclusão sobre o período
  permite, sem abrir mão da garantia no banco.
- **Inativar o aluno cancelando a matrícula junto:** prático, mas cancelaria
  sem querer algo que envolve dinheiro.

## Consequências

- O preço que o aluno contratou fica registrado e não muda; a cobrança (etapa
  6) parte do valor guardado na matrícula.
- A garantia de "um período por vez" vale até para `INSERT` feito fora da
  aplicação.
- Mais uma extensão do PostgreSQL (`btree_gist`), que exige o dono do banco na
  migration; em banco gerenciado, costuma estar na lista liberada.

## Pendências

- **Trancamento** (pausar a matrícula por viagem ou lesão, estendendo o fim).
- **Troca de plano no meio do período** (hoje: cancelar e matricular de novo).
- **Dia de vencimento** das mensalidades: decisão da etapa 6.
