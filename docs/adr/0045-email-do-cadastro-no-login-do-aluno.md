# ADR 0045 — O login do aluno acompanha o e-mail do cadastro

- **Status:** Aceito
- **Data:** 2026-10-29

## Contexto

O cadastro do aluno e o login dele guardam o e-mail cada um no seu lugar. Num sentido eles já andavam
juntos: o aluno que corrige o e-mail no primeiro acesso leva o cadastro junto (ADR 0038). No outro, não.
Quando a recepção trocava o e-mail no cadastro de um aluno que já tinha login, o login ficava com o
e-mail antigo, e o aluno ficava sem saída:

- entrar com o e-mail novo: "E-mail ou senha inválidos";
- primeiro acesso (ADR 0039): nada chega, porque ele já tem login;
- "esqueci a senha" com o e-mail novo: nada chega, porque nenhum login tem esse e-mail.

As respostas são iguais de propósito (não revelam quem é aluno), e por isso nada dizia o que estava
errado. O dono achou o problema em 29/10, testando como recepção, e decidiu:

- **O login acompanha o cadastro, e o e-mail novo volta a ser "não confirmado".** No próximo acesso,
  o aluno confirma com o código que chega no e-mail novo, como no primeiro acesso (ADR 0038). Isso
  não dá poder novo à recepção: ela já gera senha temporária para o aluno (ADR 0010).

Ficaram de fora o login sem confirmação nova (um e-mail digitado errado ficaria no login sem
ninguém perceber) e o login que fica como está, com só um aviso na tela.

## Decisões

- Editar o cadastro (`PUT /api/alunos/{id}`) leva o e-mail ao login, **na mesma transação**: ou os dois
  mudam, ou nenhum. A senha e as sessões continuam. A trilha do login registra `EMAIL_DO_CADASTRO`.
- A comparação é com o e-mail **do login**, e não com o anterior do cadastro: um login que ficou para
  trás antes desta regra volta a acompanhar na próxima edição do cadastro, mesmo que o e-mail do
  cadastro não mude. O mesmo e-mail em maiúsculas não muda nada, nem tira a confirmação.
- O e-mail novo já usado por outro login: **409**, e nada muda. Quem barra é a checagem, antes de
  alterar o login. Se duas edições correrem ao mesmo tempo, quem barra é o índice único do banco, e a
  resposta é o mesmo 409.
- **Tirar o e-mail de quem tem login: 400** ("o aluno tem login: o e-mail não pode ficar vazio"). Sem
  e-mail, o aluno não entraria nem receberia o código. O cadastro que já estava sem e-mail (a regra
  antiga deixava tirar) continua editável, e o login dele fica como está.
- Enquanto não confirma, o aluno entra só para confirmar ou corrigir o e-mail, como no primeiro acesso.
  Com o e-mail desligado (sem `EMAIL_HOST`), não há confirmação: o login só troca o e-mail.
- Só a recepção ou o ADMIN editam o cadastro; o caminho pelo login (o aluno corrige no primeiro
  acesso) continua o do ADR 0038. Um não chama o outro: não há ida e volta.

## Consequências

- A sessão que já estava aberta continua até renovar o token (ADR 0033); a partir daí, o aluno só
  confirma o e-mail antes de seguir. Não derrubamos a sessão: a senha não mudou, e quem está no app é
  o próprio aluno.
- Até confirmar, o "esqueci a senha" não vale para o e-mail novo: ele só atende e-mail confirmado
  (ADR 0038), para um e-mail digitado errado não entregar a conta a um estranho. Quem trocou o e-mail
  e esqueceu a senha antes de entrar pede à recepção a senha temporária.
- Os códigos que já tinham ido para o e-mail antigo deixam de valer: cada código é do e-mail para o
  qual foi (ADR 0038).
