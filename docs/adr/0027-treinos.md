# ADR 0027 — Treinos: catálogo de exercícios, ficha atual e histórico

- **Status:** Aceito
- **Data:** 2026-10-21

## Contexto

A etapa 7 traz as fichas de treino: o instrutor monta a ficha do aluno, e o aluno a
consulta. O módulo `treinos` existia só como pacote vazio (ADR 0002). O instrutor já vê dos
alunos só o necessário para montar o treino (nome, nascimento, observações, se está ativo;
ADR 0009).

As regras de negócio abaixo foram decididas com o dono antes da implementação.

## Decisões do dono

### Quem faz o quê
- **Qualquer INSTRUTOR**, e o ADMIN, monta e altera a ficha de qualquer aluno: cobre folga
  e troca de turno. Fica registrado quem montou cada ficha.
- A **RECEPCAO vê** a ficha atual e o histórico, sem alterar (para tirar dúvida do aluno no
  balcão).
- O **ALUNO só consulta** a ficha atual e o histórico dele. Registrar o treino feito ou a
  carga usada fica para depois, se fizer falta.

### Catálogo de exercícios
- Os instrutores (e o ADMIN) mantêm um catálogo de exercícios da academia: nome, grupo
  muscular e uma observação de execução. A ficha escolhe os exercícios dele, para o mesmo
  exercício não aparecer com nomes diferentes.

### A ficha
- Dividida em **divisões** (Treino A, B, C…), cada uma com exercícios **em ordem**: séries,
  repetições, carga, descanso e observação.
- Para **qualquer aluno ativo**, com ou sem matrícula vigente (a ficha pode ser montada na
  avaliação, antes de a matrícula começar). Aluno inativo não ganha ficha nova, mas continua
  vendo as antigas.
- **Uma ficha atual por aluno.** Montar uma nova encerra a anterior, que vai para o
  histórico e nunca mais muda nem é apagada.
- **A ficha atual pode ser corrigida** (por exemplo, subir a carga), e cada mudança vai
  para a trilha de auditoria: quem, quando e o quê. Ficha nova é só quando o instrutor monta
  outro treino.
- **Data de revisão opcional.** Os instrutores têm a lista das fichas atuais com a revisão
  vencida. A ficha vencida continua valendo.

## Decisões da implementação

### Formato dos campos
- **Repetições e carga são texto curto**, não número: "8 a 12", "até a falha", "20 kg",
  "elástico vermelho", "peso do corpo". Repetições até 20 caracteres, carga até 30. Séries
  de 1 a 20; descanso em segundos, de 0 a 600, opcional; observação até 500.
- Até **7 divisões** por ficha, cada uma com nome (até 40 caracteres, por exemplo "A —
  peito e tríceps") e de **1 a 20 exercícios**. Um mesmo exercício pode repetir em divisões
  diferentes.
- O **grupo muscular** é uma lista fixa (peito, costas, ombros, bíceps, tríceps, antebraço,
  abdômen, quadríceps, posteriores, glúteos, panturrilha, corpo inteiro, cardio), para
  filtrar o catálogo. Mudar a lista é uma migração.

### Catálogo
- Nome único entre os ativos, sem diferenciar maiúsculas e acentos, como nos planos (ADR
  0013). Exercício não se apaga: é **inativado**, sai das fichas novas e continua nas
  antigas.
- A ficha **copia o nome** do exercício, como a matrícula copia o do plano: renomear o
  exercício não muda o histórico.

### Ficha
- Montar a ficha nova e encerrar a anterior acontecem na mesma transação, com a linha do
  aluno travada (como matricular): duas fichas ao mesmo tempo para o mesmo aluno passam uma
  de cada vez, e o banco garante uma só atual por aluno (índice único parcial).
- A correção da ficha atual troca as divisões inteiras e usa `If-Match` (edição simultânea,
  ADR 0009): duas correções ao mesmo tempo não se sobrescrevem.
- A trilha ganha as entidades `EXERCICIO` e `FICHA` (ADR 0021).

## Alternativas descartadas
- **Instrutor responsável por aluno:** exigiria atribuir e trocar responsável; a academia é
  pequena, e qualquer instrutor atende qualquer aluno.
- **Toda mudança vira ficha nova:** o histórico ficaria cheio de fichas quase iguais; a
  trilha já guarda as correções.
- **Carga em número (kg):** não representa elástico, peso do corpo nem carga por lado.

## Consequências
- O aluno vê só a própria ficha (o id vem do token, como em `/api/matriculas/me`).
- Registro de treino feito e evolução de carga podem vir depois, sem mudar a ficha.
