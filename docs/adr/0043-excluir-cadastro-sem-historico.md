# ADR 0043 — Excluir o cadastro que nunca foi usado

- **Status:** Aceito (muda "cadastros não se apagam" dos ADRs 0008, 0011, 0013 e 0027 para quem não tem histórico); o ADR 0047 deu ao instrutor a exclusão do exercício
- **Data:** 2026-10-29

## Contexto

Até aqui nenhum cadastro se apagava: aluno e usuário são inativados, plano sai de oferta e
exercício é inativado. O usuário da aplicação no banco nem tinha `DELETE` nessas tabelas (revisão
geral, B4). Mas um cadastro feito por engano (plano com o preço errado e nunca contratado, aluno
duplicado, exercício com o nome errado) fica para sempre nas listas de inativos.

## Decisões (do dono, 29/10)

- **O ADMIN pode excluir aluno, plano, exercício do catálogo e usuário da equipe.** Só o ADMIN,
  inclusive para o aluno (que a recepção inativa) e o exercício (que o instrutor edita).
- **Só sai o que nunca foi usado.** Com histórico, a exclusão é recusada (409) e a mensagem
  manda inativar. Nada de apagar em cascata: dinheiro, trilha e treinos não somem (ADRs 0021,
  0024, 0025 e 0027).
- **Nas telas também**, com o botão visível só para o ADMIN e uma confirmação antes.

## O que é histórico

| Cadastro | Não pode ser excluído se |
|---|---|
| Plano | alguma matrícula foi feita nele (mesmo cancelada ou trocada) |
| Exercício | está em alguma ficha (mesmo encerrada) |
| Aluno | tem matrícula, cobrança, ficha, pagamento online, código de primeiro acesso, ou um login já usado |
| Usuário | registrou algo (caixa, pagamento, matrícula cancelada, ficha...), é autor na trilha, entrou alguma vez ou abriu um cadastro |

O usuário que já entrou fica: o registro de acessos e a trilha mostram o nome dele pelo id, e o
nome sumiria. Pelo mesmo motivo, o login de aluno já usado impede excluir o aluno. O login nunca
usado sai junto com o aluno, e as sessões dele, em cascata. Uma senha errada (`LOGIN_FALHO`) não
conta como uso: qualquer pessoa que tentasse adivinhar a senha impediria a exclusão. O registro
dela fica, com a conta digitada.

## Como funciona

- `DELETE /api/<cadastro>/{id}`: 204 quando exclui, 404 quando não existe, 409 com histórico.
- **A regra mora no banco.** Cada tabela com histórico aponta para o cadastro por chave
  estrangeira, e o `DELETE` sem cascata é recusado (código `23503`), o que vira o 409. Assim, uma
  tabela nova que aponte para o cadastro já o protege sem ninguém lembrar de atualizar uma
  lista. O que não tem chave estrangeira (a trilha e os acessos, que guardam o id do autor) é
  conferido antes, no usuário.
- **A linha do cadastro é travada antes** (`FOR UPDATE`), e quem cria histórico lê o cadastro com
  trava (`FOR SHARE`, a matrícula no plano). Assim uma matrícula feita ao mesmo tempo espera a
  exclusão e não acha o plano (400), ou a exclusão espera a matrícula e é recusada (409). Sem a
  trava, a matrícula esbarrava na chave estrangeira e respondia 500.
- **Corridas que ficam:** o primeiro acesso do aluno (ADR 0039) e o login não travam o cadastro.
  Se o ADMIN excluir o aluno no mesmo instante em que ele pede o código ou cria a senha, ou o
  usuário no mesmo instante em que ele entra, a chave estrangeira barra, e aquele pedido responde
  com erro (500 ou 409), em vez de 202 ou 200. Os dados continuam certos. Exige excluir um cadastro
  nunca usado no instante em que a pessoa o usa pela primeira vez: não vale a trava em rotas sem
  login.
- **A trilha registra `EXCLUIDO`** com o último retrato do cadastro, na mesma transação. Os
  registros anteriores do cadastro ficam (retenção de 5 anos, ADR 0022).
- **O banco:** o usuário da aplicação ganha `DELETE` nas tabelas desses cadastros. Uma injeção de
  SQL passaria a apagar um cadastro sem histórico, mas não um com histórico: a chave
  estrangeira barra também o `DELETE` direto. Continua sem `TRUNCATE`.
