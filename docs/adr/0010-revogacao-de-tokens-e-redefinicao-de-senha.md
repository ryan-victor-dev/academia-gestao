# ADR 0010 — Revogação imediata de tokens e redefinição de senha

- **Status:** Aceito (atualiza o ADR 0007); o refresh token das alternativas veio no
  [ADR 0033](0033-front-end-e-sessao-renovavel.md)
- **Data:** 2026-10-16

## Contexto

O ADR 0007 escolheu tokens totalmente stateless e aceitou a consequência: um
usuário desativado continuava com o token válido por até 15 minutos. O ADR 0009
deixou duas pendências: essa revogação, e a falta de um "esqueci minha senha".

## Decisão

### Versão do token
- Cada usuário tem `versao_token` (migration V8), que vai no JWT na claim
  `versaoToken`.
- Desativar, reativar, trocar ou redefinir a senha **aumentam a versão**: todo
  token emitido antes deixa de valer na hora.
- A cada requisição, o `TokenRevogadoValidator` (ligado ao `JwtDecoder`)
  confere no banco se o usuário existe, está ativo e se a versão do token é a
  atual. É uma consulta por chave primária.
- Reativar também aumenta a versão: um token de antes da desativação não volta
  a valer.
- Trocar a senha derruba as outras sessões do usuário: se alguém roubou a
  senha, perde o acesso assim que a vítima a troca.

### "Esqueci minha senha"
- `POST /api/alunos/{id}/login/nova-senha`: ADMIN e RECEPCAO, para alunos.
- `POST /api/usuarios/{id}/nova-senha`: só ADMIN, para RECEPCAO, INSTRUTOR ou
  ALUNO. **A senha de um ADMIN nunca é redefinida por outra pessoa.** Como só
  ADMIN chama a rota, isso também impede o ADMIN de redefinir a própria senha por
  ela (para isso existe `trocar-senha`).
- A senha temporária segue as regras do ADR 0009: aparece uma única vez, com
  `Cache-Control: no-store`, exige troca no próximo acesso e não vai para o log.
  Redefinir a senha também derruba as sessões abertas.
- Usuário (ou aluno) inativo não recebe senha nova (409): reative antes.

## Alternativas consideradas

- **Token de 5 minutos:** continua stateless, mas só diminui a janela, sem
  fechá-la, e obriga a logar com mais frequência.
- **Refresh token revogável:** é o padrão de mercado, mas é bem maior (endpoint,
  tabela, rotação do refresh, mais trabalho no front-end). Continua sendo o
  caminho se a consulta por requisição virar gargalo.
- **Lista negra de tokens:** guardar cada token revogado até ele expirar. A
  versão por usuário resolve o mesmo com um número só.

## Consequências

- A API **deixa de ser 100% stateless**: cada requisição autenticada faz uma
  consulta ao banco. Medido no Docker: a requisição inteira em `/api/me` levou
  3,3 ms de mediana e 4,7 ms no p95.
- Tokens emitidos antes desta mudança (sem `versaoToken`) são recusados: no
  deploy, todo mundo precisa entrar de novo uma vez.
- Testes que montam tokens "na mão" precisam de um usuário real no banco.

## Pendências

As duas pendências registradas originalmente aqui (ADMIN que esqueceu a senha e
cadastro da equipe) foram resolvidas no
[ADR 0011](0011-gestao-da-equipe-e-recuperacao-de-admin.md).
