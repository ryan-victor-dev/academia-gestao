# ADR 0011 — Gestão da equipe e recuperação de acesso de ADMIN

- **Status:** Aceito
- **Data:** 2026-10-16

## Contexto

O ADR 0010 deixou duas pendências: não havia como cadastrar a equipe (só o
primeiro ADMIN, criado pelo `.env`), e um ADMIN que esquecesse a senha não
tinha caminho de volta, já que a API nunca deixa outra pessoa redefinir a senha
de um ADMIN.

## Decisão

### Cadastro da equipe (só ADMIN)
- `POST /api/usuarios` cria **ADMIN, RECEPCAO ou INSTRUTOR** com senha
  temporária, nas mesmas regras do ADR 0009: aparece só na resposta
  (`Cache-Control: no-store`) e precisa ser trocada no primeiro acesso. Perfil
  ALUNO é recusado (400): login de aluno nasce do cadastro do aluno.
- O ADMIN pode criar outros ADMINs. Ter mais de um reduz o risco de a academia
  ficar sem acesso.
- `POST /api/usuarios/{id}/desativar`: funcionário que saiu. As sessões dele
  caem na hora (ADR 0010).
- `POST /api/usuarios/{id}/reativar`: volta a funcionar. Reativar quem já está
  ativo não mexe nos tokens.
- Login de aluno não é desativado nem reativado por aqui (400): ele acompanha
  o cadastro do aluno (ADR 0009).

### A academia nunca fica sem ADMIN
- Ninguém desativa o próprio login (409).
- O último ADMIN ativo não pode ser desativado (409). Com a regra anterior,
  isso só acontece numa **corrida**: A desativa B enquanto B desativa A, as
  duas checagens veem "2 ADMINs ativos" e as duas passariam. Por isso, antes de
  contar, as linhas dos ADMINs ativos são travadas (`SELECT ... FOR UPDATE`,
  sempre na mesma ordem, sem risco de deadlock). A segunda transação espera a
  primeira, conta de novo e é recusada.

### Recuperação de ADMIN: comando no servidor
- `docker compose run --rm app --recuperar-admin=<e-mail>`
- O `main` detecta a opção e sobe a aplicação **sem servidor web**. Ela gera
  uma senha temporária, imprime **só no terminal** (saída padrão, nunca no
  log), exige troca no próximo acesso, derruba as sessões abertas e encerra.
- ADMIN inativo é reativado. E-mail inexistente, vazio ou de quem não é ADMIN
  falham com mensagem clara e código de saída 1, sem mexer em nada.
- O log registra a recuperação (sem a senha), para auditoria.
- Quem consegue rodar o comando já tem acesso ao servidor e ao banco: essa é a
  prova de autoridade. Nada disso fica exposto na API.

## Alternativas consideradas

- **Outro ADMIN redefinir pela API:** não resolve quando só existe um ADMIN, e
  abriria uma porta para um ADMIN tomar a conta de outro.
- **Editar a senha direto no banco:** exige gerar um hash BCrypt à mão e é fácil
  esquecer de exigir a troca e de revogar as sessões.
- **Mudar `ADMIN_SENHA` no `.env`:** o primeiro ADMIN só é criado quando não
  existe nenhum; reaproveitar isso para redefinir senha misturaria duas
  responsabilidades e deixaria a senha nova num arquivo.

## Consequências

- A equipe pode ser gerida inteiramente pela API, e um ex-funcionário perde o
  acesso na hora.
- Recuperar um ADMIN exige acesso ao servidor. Está documentado no README.
- Mudar nome ou perfil de um usuário da equipe ainda não é possível.
