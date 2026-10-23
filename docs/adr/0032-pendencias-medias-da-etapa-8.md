# ADR 0032 — Pendências médias da etapa 8: lista de alunos, contagem às cegas e bloqueios

- **Status:** Aceito (completa o ADR 0031)
- **Data:** 2026-10-23

## Contexto

O diagnóstico da etapa 8 (caixa e auditoria completa), em 2026-10-23, achou três problemas
médios, todos reproduzidos no Docker:

1. **A lista de alunos contornava o registro de consultas (M1).** O registro grava quem abriu
   o cadastro de qual aluno; buscas e listas ficaram de fora para não gerar uma linha por aluno
   listado (ADR 0031). Mas `GET /api/alunos` devolvia o mesmo que o cadastro aberto (CPF,
   e-mail, telefone e observações; para o instrutor, as observações, que podem ter dados de
   saúde), até 100 por página. Quem quisesse ler cadastros sem deixar rastro usava a lista.
2. **A contagem às cegas vazava (M2).** Fechar com um contado errado e sem justificativa
   respondia "o contado difere do esperado em -150.00": a recepção mandava 0, calculava o
   esperado (contado − diferença) e fechava com ele.
3. **Um ataque de login enchia o disco (M3).** Cada tentativa bloqueada pelo limite virava
   uma linha guardada 1 ano, sem login e sem limite de taxa: ~300 por segundo de uma máquina
   só (3 milhões de linhas = 667 MB). E a retenção varria a tabela inteira todo dia, mesmo sem
   nada vencido; com dezenas de milhões de linhas, passaria dos 5 s do `statement_timeout` e
   nada mais seria apagado.

## Decisões

### Lista de alunos com o resumo (decisão do dono)
- `GET /api/alunos` devolve, para todos os perfis, só id, nome, data de nascimento e se está
  ativo: o suficiente para achar o aluno e abrir o cadastro. CPF, e-mail, telefone e
  observações saem só pelo cadastro aberto, que vai para o registro de acessos.
- A busca por CPF continua para a recepção e o ADMIN (o instrutor busca só por nome).
- Descartado: manter a lista completa e registrar cada busca (quem, o termo, quantos vieram).
  O registro ficaria maior e não diria quais cadastros foram lidos.

### O fechamento recusado não diz a diferença
- A mensagem passa a ser só "o contado difere do esperado: escreva uma justificativa". Cada
  tentativa diz "bate" ou "não bate"; achar o esperado chutando centavo por centavo é inviável.
- O dono decidiu não registrar as tentativas recusadas.

### Bloqueio registrado uma vez por janela (decisão do dono)
- O login ou a troca de senha recusados pelo limite entram no registro só uma vez por conta e
  IP dentro da janela do limite por conta (15 min). Um ataque de horas vira poucas linhas, e o
  ADMIN ainda vê quem foi atacado e de onde. Senhas erradas continuam uma linha cada (o próprio
  limite as segura).
- Um comando só (`INSERT ... WHERE NOT EXISTS`), fora de transação, como o resto do registro
  (ADR 0031). Tentativas simultâneas podem gravar uma ou outra linha a mais; o número não
  cresce com a duração do ataque.

### Retenção sem varrer a tabela (V39)
- Índices BRIN em `momento` nos acessos e na trilha (as duas tabelas só crescem em ordem de
  tempo; 24 kB com 3 milhões de linhas), e as funções de retenção ordenando por `momento` (a
  mesma ordem do id). Ordenando pelo id, o plano genérico da função percorria a chave primária
  inteira. Medido num Postgres 17 com 3 milhões de linhas: de 460 ms para menos de 1 ms sem nada
  vencido, e lotes de 10 mil vencidos em menos de 30 ms.

## Consequências
- Uma tela que mostre CPF ou telefone na lista de alunos precisa abrir o cadastro de cada um, e
  cada abertura fica registrada. É a intenção.
- O registro de acessos não conta mais quantas tentativas um ataque fez: mostra que houve
  bloqueio, de qual IP e quando. O total segue no log da aplicação.

## Verificação
- Testes novos para as três correções; mutações à mão: 1 no fechamento, 10 no bloqueio e na
  retenção (uma pediu o teste da troca de senha bloqueada, que faltava desde a etapa 8) e 4 na
  lista. Todas morreram.
- Docker de verdade: o 400 do fechamento sem números; 400 tentativas de login numa conta falsa
  gravaram 1 bloqueio (antes, 384); a lista só com o resumo, e o cadastro aberto registrado.
