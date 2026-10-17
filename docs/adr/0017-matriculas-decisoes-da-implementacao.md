# ADR 0017 — Matrículas: decisões tomadas durante a implementação

- **Status:** Aceito (complementa o ADR 0016)
- **Data:** 2026-10-17

## Contexto

O ADR 0016 registrou as regras de negócio das matrículas antes do código. A
implementação, os testes de mutação e a validação no Docker trouxeram decisões e
lições que ele não previa. Pelo ADR 0001, um ADR aceito não é editado; elas ficam
aqui.

## Decisões

### A matrícula guarda também o nome do plano
- Além do valor e da duração, a matrícula copia o **nome** do plano (`plano_nome`,
  migration V12). É o retrato do que foi contratado, como numa nota fiscal: o
  `planoId` diz de onde veio; a cópia diz o que valia no dia.
- Motivo: o aluno não consulta plano inativo (404, ADR 0013). Sem a cópia, quem tem
  matrícula num plano renomeado ou tirado de linha não saberia o nome do próprio
  plano no `/api/matriculas/me`.
- Matrículas anteriores à V12 recebem o nome atual do plano: o do dia não tinha sido
  guardado. Esse preenchimento foi conferido num PostgreSQL descartável, porque nos
  testes a tabela está vazia quando a V12 roda.

### A trava do aluno é obrigatória, não otimização
- Matricular e inativar travam a linha do aluno (`SELECT ... FOR UPDATE`) antes de
  conferir, e sempre nessa ordem (aluno, depois matrículas).
- **Entre matrículas simultâneas do mesmo aluno:** sem a trava, elas chegam juntas
  ao `INSERT`, cada uma espera as outras na restrição de exclusão e o PostgreSQL
  acusa *deadlock*: o cliente receberia 500. Com a trava, passam uma de cada vez e
  as demais recebem 409. A restrição do banco continua como última garantia (para
  `INSERT` fora da aplicação), e o erro dela também vira 409.
- **Entre matricular e inativar:** sem qualquer uma das duas travas, os testes
  pegaram o estado proibido, um aluno inativo com matrícula valendo.

### Portas que devolvem dados, não exceções
- `alunos` expõe `AlunosParaMatricula` (trava e diz se o aluno existe e está ativo,
  via `EstadoDoAluno`); `planos` expõe `PlanosParaMatricula` (condições atuais, via
  `PlanoOfertado`).
- Elas devolvem dados porque os tratadores de erro de cada módulo valem só para os
  controllers do próprio pacote: uma exceção de `alunos` dentro de uma requisição de
  matrícula chegaria sem tratamento e viraria 500. Quem decide a resposta HTTP é o
  módulo que chamou.
- Aluno ou plano inexistente informado no corpo da matrícula é **400** (dado
  inválido no pedido), não 404: o recurso da URL (`/api/matriculas`) existe.

### Inversão de dependência na inativação
- `alunos` declara a interface `ImpedimentoParaInativar`; `matriculas` a implementa.
  O `AlunoService` recebe a lista de todos os impedimentos, sem saber quem são. A
  cobrança (etapa 6) pode acrescentar o seu sem mudar `alunos`.

### Detalhes da API
- `GET /api/matriculas/vigente?alunoId=` responde `false` para aluno inexistente, em
  vez de 404: o instrutor chega ao id pela lista de alunos, e a rota não precisa de
  mais uma porta só para isso.
- O histórico (`GET /api/matriculas?alunoId=`) exige o aluno e não é paginado: um
  aluno tem poucas matrículas por ano. Se crescer, ganha paginação como a lista de
  alunos.
- Cancelar trava a matrícula: o segundo cancelamento simultâneo espera e recebe 409
  ("já cancelada"), em vez de um erro de versão.

## Lições registradas

- **Teste de corrida precisa que os dois lados ganhem.** Na primeira versão do teste
  "inativar e matricular ao mesmo tempo", a matrícula ganhou as 30 rodadas: a
  inativação passa pelo MockMvc e chegava sempre depois. Um atraso aleatório de 0 a
  3 ms equilibrou o placar. O teste imprime quantas vezes cada lado ganhou.
- **Mutação que não compila não é mutação pega.** Uma mutação renomeou um método só
  no repositório; o teste falhou por erro de compilação, não por ordem errada. O
  script de mutação passou a separar os dois casos.
- **Mutante equivalente:** liberar a recepção no `/api/matriculas/me` não muda nada,
  porque o token dela não tem `alunoId` e a checagem seguinte devolve o mesmo 403.
  São duas camadas, e cada uma tem teste próprio.

## Consequências

- O `ArquiteturaTest` lista as portas novas: `alunos` expõe `AlunosParaMatricula`,
  `EstadoDoAluno` e `ImpedimentoParaInativar`; `planos` expõe `PlanosParaMatricula` e
  `PlanoOfertado`.
- Testes que criam matrículas precisam apagá-las antes de apagar planos, alunos ou
  usuários, por causa das chaves estrangeiras; vários testes antigos apagam essas
  tabelas inteiras.
