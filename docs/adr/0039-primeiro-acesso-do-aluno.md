# ADR 0039 — Primeiro acesso do aluno

- **Status:** Aceito
- **Data:** 2026-10-24

## Contexto

Até aqui, o login do aluno só nascia pela recepção (ADR 0010): ela cria o acesso, o sistema mostra
uma senha temporária, e alguém a entrega ao aluno. Com o e-mail ligado (ADR 0038), o aluno já pode
provar que o e-mail do cadastro é dele. O dono pediu (2026-10-24) que o aluno novo crie o próprio
login. Decisões dele:

- **Só o "primeiro acesso"**, na tela de login: o aluno digita o CPF e o e-mail do cadastro, recebe
  um código de 6 dígitos nesse e-mail e cria a senha. **Sem o convite por e-mail** (a recepção
  mandar o acesso ao criar): o caminho da senha temporária continua como está.
- **Só aluno ativo** faz o primeiro acesso. O inativo que já tinha login continua entrando, só de
  leitura (ADR 0029); o que nunca teve, pede à recepção.

Desaconselhado e fora: "criar conta" aberto, sem um cadastro por trás (logins sem aluno, cadastros
falsos). A equipe continua sendo criada só pelo ADMIN.

## Decisões

### Quem pode (parte 18)
- O aluno **ativo**, com **este CPF e este e-mail no cadastro** (o e-mail sem diferenciar
  maiúsculas, o CPF com ou sem pontuação), **ainda sem login**, e com um e-mail que **nenhum outro
  login usa** (senão o login não poderia ser criado; o caso típico é o e-mail de um familiar). Quem
  não tem e-mail no cadastro segue pela recepção.
- O CPF é a segunda prova: o e-mail sozinho é fácil de adivinhar, o CPF junto não basta para entrar
  (o código vai para o e-mail do cadastro, e não para o digitado).
- O auth não depende do módulo de alunos (ADR 0015): a pergunta "há um aluno ativo com este CPF e
  este e-mail?" passa pela interface `CadastroParaPrimeiroAcesso`, no compartilhado, que o módulo
  de alunos implementa.

### Como funciona (parte 18)
- `POST /api/auth/primeiro-acesso` (`cpf`, `email`) manda o código; `POST
  /api/auth/primeiro-acesso/senha` (`cpf`, `email`, `codigo`, `senha`) cria o login. Rotas sem login;
  `GET /api/auth/opcoes` diz se a tela oferece (`primeiroAcesso`, só com o e-mail ligado).
- **O código é do aluno**, porque o login ainda não existe: `codigos_de_email.aluno_id` (V51), com a
  finalidade `PRIMEIRO_ACESSO`; as outras finalidades continuam presas ao login. Mesmas regras do
  ADR 0038: só o hash, 15 minutos, uso único, 5 tentativas, 5 por hora e 1 por minuto. Em vez da
  linha do aluno (outro módulo), uma trava do Postgres pelo id do aluno (`pg_advisory_xact_lock`)
  faz dois pedidos ao mesmo tempo contarem um depois do outro.
- O código vai para o e-mail do cadastro e vale só para ele: se a recepção muda o e-mail depois, o
  código para de servir.
- **Nada revela quem é aluno**, como no "esqueci a senha": o pedido responde sempre 202, quem não pode
  gasta o mesmo BCrypt, o e-mail sai em segundo plano, e "muitos códigos" e servidor de e-mail fora do
  ar ficam só no log. Os limites são os do login, pelo e-mail digitado (20 por minuto por IP; o código
  errado conta como senha errada).
- O login nasce com a senha escolhida (que segue a política, 12 caracteres), **com o e-mail já
  confirmado** (o código chegou nele) e sem senha temporária: o aluno entra direto. A trilha registra
  `CRIADO_NO_PRIMEIRO_ACESSO`, sem autor (foi o próprio aluno, ainda sem login).
- Antes de gravar, confere tudo de novo: se a recepção criou o login (ou mudou o cadastro) depois do
  código, 409; dois ao mesmo tempo, o UNIQUE do banco barra o segundo (409).

## Consequências

- Sem um serviço de e-mail configurado, o primeiro acesso não aparece: a recepção continua criando
  os acessos com a senha temporária.
- O aluno com e-mail errado no cadastro, ou com o e-mail de outro login, não recebe nada, e a tela
  não pode dizer por quê (diria quem é aluno): ela orienta a procurar a recepção se o código não chegar.
- Quem sabe o CPF e o e-mail de um aluno consegue mandar um código para ele, mas não entra: o código
  vai para o e-mail do aluno. É o mesmo risco do "esqueci a senha", com os mesmos limites.
