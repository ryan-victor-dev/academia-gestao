# ADR 0041 — Diagnóstico completo (27/10): os três médios

- **Status:** Aceito
- **Data:** 2026-10-28

## Contexto

O diagnóstico completo de 27/10 (reta final, antes de ir ao ar) não achou nada alto e achou três
médios: o pagamento simulado num servidor de verdade (M1), o backup em claro e legível por todos na
máquina (M2) e a força bruta do código do "esqueci a senha" (M3). O dono pediu (28/10) que os três
sejam resolvidos, um por commit, e decidiu o que era dele: cifrar o backup com `age` (M2) e um teto
de 10 códigos por pessoa em 24 horas (M3).

## Decisões

### M1 — O simulado não sobe num servidor de verdade
Antes: com `PAGAMENTOS_PROVEDOR=simulado`, a rota de simular deixa o aluno marcar a própria
cobrança como paga sem pagar, e nada impedia ligar o simulado num servidor com domínio (a
aplicação nem recebia o `DOMINIO`, só o Caddy). Um `.env` copiado do ambiente de teste virava
fraude.

Agora o Compose passa o `DOMINIO` à aplicação (`academia.dominio`), e o `ProvedorSimulado` recusa
subir quando ele não é vazio nem `localhost` (`TravaDoSimulado`, a mesma regra do
`scripts/usuario-validacao.sh`). A aplicação inteira não sobe, com a mensagem no log: melhor fora do
ar do que recebendo "pagamentos" de mentira. Sem o simulado, o `DOMINIO` não muda nada.

### M2 — Backup cifrado e só do dono (decisão do dono: cifrar com `age`)
Antes: `backups/diario/*.dump` saía `rw-r--r--`, numa pasta `drwxr-xr-x`: qualquer usuário do
servidor lia CPF, e-mail, telefone e a trilha de todos os alunos (LGPD), e a cópia que o README
manda levar para fora do servidor ia em claro.

Agora:
- **Permissões:** a rotina roda com `umask 077` e, a cada backup, fecha a pasta (`700`) e os
  arquivos (`600`), inclusive os de antes desta regra.
- **Cifra:** o backup sai cifrado com o [age](https://age-encryption.org) (`.dump.age`), com a
  chave pública do `.env` (`BACKUP_CHAVE_PUBLICA`, obrigatória: sem ela, o Compose não sobe). A
  chave privada é gerada fora do servidor e fica com o dono; sem ela, nenhum backup volta. Por isso
  o README manda guardá-la em dois lugares. O age é simples, sem configuração, e já vem no Alpine:
  a imagem do backup passa a ser a do Postgres mais o age (`scripts/backup/Dockerfile`).
- **O dump em claro não toca o disco do servidor:** é gerado no `/tmp` do container (memória),
  conferido com `pg_restore --list` (depois de cifrado, sem a chave privada, ninguém aqui consegue
  conferir), cifrado e apagado.
- **Restaurar** pede a chave privada (`BACKUP_CHAVE=arquivo`) e decifra antes de tudo, num
  container do backup, com a chave só para leitura: chave errada ou faltando, nada muda e a
  aplicação nem para. O backup do estado anterior (para desfazer) também sai cifrado. Os backups
  antigos, em claro, ainda restauram sem a chave e saem com a rotação normal (7 diários e 4 semanais).
- Corrigido de passagem: o script de restauração conferia o arquivo relativo à pasta de onde foi
  chamado, mas o lia depois de ir para a pasta do projeto.

Consequência: perder a chave privada é perder todos os backups. É o preço de a cópia fora do
servidor não expor ninguém; a alternativa (só as permissões) deixava a cópia em claro.

