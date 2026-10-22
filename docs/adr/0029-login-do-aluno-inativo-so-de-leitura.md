# ADR 0029 — Login do aluno inativo, só de leitura

- **Status:** Aceito (muda "Login acompanha o aluno" do ADR 0009)
- **Data:** 2026-10-22

## Contexto

Os ADRs 0008 e 0027 e a rota `/api/matriculas/me` prometem que o aluno inativo continua
vendo os próprios dados (direito de acesso, LGPD, art. 18). Mas o ADR 0009 desligava o
login na inativação: o aluno inativo não entrava, e nenhuma das rotas `/me` (cadastro,
matrículas, cobranças, fichas) funcionava para ele. Os testes dessas rotas usavam token
simulado, que pula a validação no banco, e não pegaram a contradição (diagnóstico da
etapa 7, D1).

## Decisões (do dono)

- **Inativar o aluno deixa o login dele só de leitura**, em vez de desligá-lo. Ele entra,
  consulta (GET) e troca a própria senha. Qualquer outra coisa dá 403.
- **Consulta inclui os planos ativos**, como para qualquer aluno: não é dado pessoal, e
  pode ajudar a voltar. A regra fica simples: o inativo faz tudo que é consulta, nada que
  altere.
- **Sem senha nova nem login novo para o inativo.** A recepção continua recusando os dois
  ("reative antes"). Quem esqueceu a senha ou nunca teve login pede os dados na recepção,
  que vê tudo.
- **Reativar** devolve o uso normal.

## Como funciona

- `usuarios.somente_leitura` (V34), só em login de aluno (`CHECK`). Restringir e liberar
  revogam os tokens: o token de antes da inativação cai na hora (com acesso completo), e
  o token só de leitura cai na reativação.
- O token leva a claim `somenteLeitura`, que vira a autoridade `SOMENTE_LEITURA` junto com
  `ROLE_ALUNO`. As rotas `/me` continuam valendo pelo perfil. Uma regra na cadeia de
  segurança deixa passar só `GET` quando há `SOMENTE_LEITURA`. Assim, uma rota de aluno
  que altere dados, criada no futuro (como "registrar o treino feito"), já nasce fechada
  para o inativo, sem ninguém lembrar.
- A troca de senha fica antes dessa regra, como já ficava para a senha temporária.
- A V34 devolveu o login, só de leitura, aos alunos que já estavam inativos, com os tokens
  antigos revogados.
- A trilha do login registra `SOMENTE_LEITURA` e `LIBERADO` (antes: `DESATIVADO` e
  `REATIVADO`), com o campo `somenteLeitura`. `/api/me` também o mostra.

## Alternativas descartadas
- **Leitura por um prazo** depois da inativação (ex.: 90 dias): mais regras (o prazo, o
  que acontece no fim) sem pedido do dono.
- **Sem acesso, só pela recepção:** o direito de acesso seria atendido a pedido. Mas os
  ADRs já prometiam o acesso pelo sistema, e a academia teria mais trabalho no balcão.
- **Autorização rota a rota** (`@PreAuthorize` com "não somente leitura" em cada rota que
  altera): cada rota nova precisaria lembrar. A regra na cadeia vale para todas.

## Consequências
- Ex-alunos com login continuam entrando, para sempre, enquanto inativos. Se um dia o
  dono quiser um prazo, é um ADR novo.
- O limite de requisições e o de tentativas de login valem para eles como para qualquer
  um.

## Verificação
- Testes com logins de verdade (inativo entra, vê as quatro rotas `/me` e os planos,
  troca a senha e continua só de leitura; tokens de antes da inativação e da reativação
  caem), a regra sozinha e ligada à cadeia, a entidade e a migração (banco próprio até a
  V33, os dados, e então a V34).
- 22 mutações à mão, 21 pegas. A que sobra é equivalente: tirar `perfil = 'ALUNO'` da
  V34 não muda nada, porque só login de aluno tem `aluno_id` (`CHECK` da V3). Na primeira
  rodada, o build incremental perdeu classes e contou mutantes como mortos sem serem: a
  rodada foi refeita do zero, com o build limpo a cada mutante suspeito.
- Atualização de verdade numa instalação isolada: a versão anterior (V33), com uma aluna
  inativada e sem acesso; depois, o código novo no mesmo banco. A V34 devolveu o login
  dela só de leitura, e o fluxo novo de inativar e reativar funcionou (18 ok).
- e2e numa instalação do zero: 118 ok.
