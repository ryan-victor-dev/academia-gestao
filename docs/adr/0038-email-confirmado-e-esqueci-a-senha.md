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
