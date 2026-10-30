# ADR 0047 — Novas funções pedidas pelo dono (equipe, cadastro, boleto, notícias e avisos)

- **Status:** Aceito
- **Data:** 2026-10-30

## Contexto

Em 30/10 o dono pediu sete funções novas: CPF no cadastro da equipe; identidade de gênero e orientação
sexual de alunos e equipe; esconder da aba Equipe quem foi desativado; boleto gerado no balcão; o
instrutor excluir exercício cadastrado por engano; uma página de notícias, comunicados e eventos com
fotos, vídeos, links e documentos, como página inicial; e avisos por e-mail e SMS das faturas (antes de
vencer e em atraso) e das notícias e eventos. Ele decidiu as regras de negócio abaixo antes de começar.
Cada função entra numa parte (um commit), e a seção dela registra o que mudou no sistema.

## Decisões do dono

1. **CPF da equipe:** obrigatório em todo cadastro novo, sem repetir. Os antigos ficam sem CPF até o
   ADMIN preencher; a tela avisa.
2. **Identidade de gênero e orientação sexual:** opcionais, com "prefiro não informar" entre as opções.
   Orientação sexual é dado pessoal sensível (LGPD, art. 5º, II): **só o ADMIN e a própria pessoa veem**;
   recepção e instrutor, não. A tela usa "identidade de gênero" e "orientação sexual" (e não "opção
   sexual"), com as identidades e orientações usadas hoje e um campo "outra" para a pessoa descrever.
3. **Equipe desativada:** sai da lista principal da aba Equipe e aparece só no filtro "inativos", na
   mesma aba.
4. **Boleto no balcão:** a recepção e o ADMIN geram o boleto da cobrança para o aluno, pelo mesmo
   pagamento online do ADR 0034 (com o provedor desligado, não há boleto, como hoje no app do aluno).
5. **Instrutor exclui exercício:** qualquer exercício do catálogo **nunca usado** em ficha, pela mesma
   regra do ADR 0043 (quem barra é a chave estrangeira; com histórico, 409 e a sugestão de inativar).
6. **Notícias e eventos:** ADMIN e recepção publicam notícias, comunicados e eventos; **só quem tem login**
   vê (aluno e equipe), e é a página inicial depois de entrar. Anexos guardados no servidor e no backup:
   fotos até 5 MB, documentos (PDF) até 10 MB, vídeos curtos até 50 MB; vídeo longo vai por link.
7. **Avisos de fatura:** por e-mail e SMS, uma vez por dia às **9h** no horário da academia: 5, 3 e 1 dia
   antes do vencimento, no dia, e com 1, 3 e 5 dias de atraso. Antes de cada envio, o sistema confere de
   novo que a cobrança continua em aberto: **quem pagou não recebe aviso de atraso**. Os de atraso dizem
   que o atraso aumenta o valor (multa e juros) e bloqueia o acesso; os de antes do vencimento dizem o
   mesmo em tom amigável, como quem adianta uma coisa boa. O aviso de fatura **não se desliga** (é do
   contrato).
8. **Avisos de notícias e eventos:** por e-mail e SMS, e **a pessoa pode desligar** no app.
9. **SMS:** construído com um envio simulado, atrás de uma porta, como o pagamento online (ADR 0034); a
   empresa (Zenvia, Twilio, AWS SNS…) é escolhida antes de ir ao ar. Sem empresa, o SMS fica desligado.

Ficaram de fora: página de notícias aberta na internet (fotos e documentos ficariam públicos e
indexados), gênero obrigatório, toda a equipe ver o gênero e a orientação, e vídeos de até 500 MB.

## O que mudou, parte a parte

### Equipe desativada só na aba dos inativos (decisão 3)
A tela Equipe tem duas abas: "Ativos", que abre primeiro, e "Membros da equipe inativos", onde ficam
quem foi desativado e o botão de reativar. A API não mudou (`GET /api/usuarios` traz a equipe inteira,
que é pequena), e a tela separa pela situação. Os logins de alunos continuam à parte, como antes.
