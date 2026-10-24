# ADR 0038 — E-mail confirmado e "esqueci a senha"

- **Status:** Aceito
- **Data:** 2026-10-24

## Contexto

Quem esquecia a senha dependia da recepção (aluno) ou do ADMIN (equipe) para redefini-la, e o
e-mail cadastrado nunca era conferido: um e-mail digitado errado, ou falso, ficava no sistema
sem ninguém perceber. O dono pediu (2026-10-24) o "esqueci a senha" na tela de login e a
confirmação do e-mail. Decisões dele:

- **"Esqueci a senha" para alunos e equipe (recepção e professores), menos o ADMIN**: quem
  invadisse o e-mail do ADMIN levaria o sistema inteiro. O ADMIN continua com a recuperação pelo
  servidor (ADR 0022).
- **Confirmação no primeiro acesso**, para aluno e equipe: o app pede a confirmação antes de
  liberar o resto, como já faz com a senha temporária; dá para corrigir o e-mail ali.
- **Código de 6 dígitos** no e-mail (e não link), para confirmar e para recuperar: funciona no
  celular com o app instalado, sem sair dele.

## Decisões

### Envio de e-mail por SMTP, desligado por padrão (parte 15, decisão técnica)
- `Correio`, no módulo compartilhado: envia texto simples por SMTP, configurado no `.env`
  (`EMAIL_HOST`, porta, usuário, senha, STARTTLS e remetente). Qualquer serviço de e-mail serve;
  a escolha fica para depois, sem mudar código.
- **Sem servidor configurado, o e-mail fica desligado**, e nada que dependa dele é exigido:
  ninguém precisa confirmar o e-mail, e o "esqueci a senha" não aparece. Senão, ninguém entraria
  até alguém configurar o SMTP. É o mesmo esquema do pagamento online (ADR 0034).
- O envio é chamada de rede: nunca dentro de uma transação do banco, com teto de 10 s por etapa
  da conversa com o servidor. Falha vira `CorreioIndisponivelException`, e o log leva só o tipo
  do erro (a mensagem do servidor pode trazer o endereço, que é dado pessoal).
- Para testar sem mandar nada para fora: o Mailpit, uma caixa de e-mails local, sobe com
  `--profile email-de-teste` e mostra os e-mails em http://localhost:8025 (só nesta máquina).
  O mesmo Mailpit, pelo Testcontainers, prova o envio SMTP de verdade nos testes.

### Confirmação no primeiro acesso (parte 16)
- `usuarios.email_confirmado_em` (V50). Precisa confirmar: e-mail ligado, perfil que não é ADMIN,
  e ainda não confirmado. Os logins que já existiam também confirmam, no próximo acesso.
- Como a senha temporária (ADR 0010): o token sai com `confirmarEmail` e **sem o perfil**, só a
  autoridade `EMAIL_NAO_CONFIRMADO`. Nenhuma regra de perfil deixa passar, e a regra geral barra
  até o que não exige perfil. Liberados só `/api/me` e `/api/auth/email/**` (mandar o código,
  confirmar, corrigir o e-mail). A senha temporária vem antes: com ela, nem o e-mail.
- Confirmado, a tela renova o token (`/api/auth/renovar`), que já sai sem a restrição.
- **Os códigos** (`codigos_de_email`, V50): 6 dígitos de um gerador seguro, só o hash (BCrypt) no
  banco; valem 15 minutos, uma vez só, com até 5 tentativas (a tentativa errada fica gravada, em
  transação própria); o novo encerra o anterior; no máximo 5 por hora, com 1 minuto entre um e
  outro (429 com `Retry-After`). A linha do usuário é travada ao emitir: dois pedidos ao mesmo
  tempo saem um 204 e um 429, nunca dois códigos valendo. O usuário da aplicação não apaga códigos.
- **Corrigir o e-mail**: só antes de confirmar; o novo não pode ser de outro login (409); o código
  vai para o novo. No aluno, o cadastro acompanha: o auth publica `EmailDoAlunoCorrigido`
  (compartilhado), e o módulo de alunos atualiza o cadastro na mesma transação (o auth não depende
  de outros módulos, ADR 0015). As duas trilhas registram.
- Servidor de e-mail fora do ar: 503 ("tente de novo em alguns minutos").
