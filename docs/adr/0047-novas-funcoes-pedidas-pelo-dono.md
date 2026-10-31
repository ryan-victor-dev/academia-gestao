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

### O instrutor exclui exercício nunca usado (decisão 5)
`DELETE /api/exercicios/{id}` passa a aceitar ADMIN e INSTRUTOR, e o catálogo mostra o botão Excluir aos
dois. A regra do ADR 0043 continua: só sai o exercício que nunca entrou numa ficha, mesmo encerrada (a
chave estrangeira barra, 409, e o caminho é inativar), com a trava contra a ficha montada no mesmo
instante e o registro `EXCLUIDO` na trilha. Aluno, plano e equipe continuam só com o ADMIN.

### CPF da equipe (decisão 1)
`usuarios` ganhou a coluna `cpf` (V55): só os 11 dígitos (`ck_usuarios_cpf`), nunca no login de aluno, cujo
CPF fica no cadastro dele (`ck_usuarios_cpf_so_da_equipe`), e sem repetir (`uq_usuarios_cpf`). A coluna
aceita nulo, e a obrigação fica na aplicação: um CHECK, mesmo `NOT VALID`, valeria também no UPDATE, e
renomear alguém de antes passaria a falhar.

- `POST /api/usuarios` exige o `cpf` (com ou sem pontuação, conferido pelos dígitos); repetido, 409 "CPF já
  cadastrado". `PUT /api/usuarios/{id}` aceita o `cpf` opcional: sem ele (ou em branco), o atual fica. O CPF
  se corrige, mas não se apaga.
- A resposta traz o `cpf` (só o ADMIN lista a equipe). A trilha registra o CPF, como já fazia com o do aluno.
- A tela pede o CPF no login novo; quem é de antes aparece com "Sem CPF" e ganha o seu pela edição.
- As classes `Cpf` e `CpfValido` saíram do módulo de alunos para o `compartilhado`: o `auth` não depende de
  outros módulos (ADR 0015).
- O primeiro ADMIN, criado a partir do `.env` na instalação (ADR 0011), continua nascendo sem CPF: o
  `.env` não tem o CPF de ninguém, e ele preenche o seu depois pela tela.

A checagem dá a mensagem clara; numa corrida, quem barra é o índice único, e só o erro dele vira o 409 do CPF.
O `CpfDaEquipeTest` força a corrida (outra transação grava o CPF sem confirmar; o pedido passa pela checagem
e espera no índice, conferido no `pg_stat_activity`). Mutantes equivalentes: tirar a checagem `existsByCpf`
do cadastro ou da edição não muda o que se vê, porque o índice dá o mesmo 409 (a checagem só evita a ida ao
banco).

### Identidade de gênero e orientação sexual (decisão 2)
Duas tabelas (V56): `identidade_dos_alunos`, pelo cadastro (o aluno pode não ter login), e
`identidade_da_equipe`, pelo login. Ficam fora do cadastro para não sair junto com o aluno que a recepção e o
instrutor consultam, nem com o retrato da trilha. Rotas próprias, com `Cache-Control: no-store`:
`/api/alunos/me/identidade` (o aluno), `/api/me/identidade` (a equipe), e `/api/alunos/{id}/identidade` e
`/api/usuarios/{id}/identidade` (só o ADMIN). A recepção e o instrutor recebem 403; o cadastro do aluno, a
lista da equipe e o `/api/me` não trazem esses campos.

- **Opções:** identidade de gênero (mulher e homem cisgênero, mulher e homem trans, travesti, pessoa não binária,
  gênero fluido, agênero) e orientação sexual (heterossexual, lésbica, gay, bissexual, pansexual, assexual,
  demissexual, queer), cada uma com "outra" (a pessoa descreve, até 50 caracteres) e "prefiro não informar".
  Nulo é "não respondido", e responder nulo apaga.
- **Trilha:** registra `IDENTIDADE_ALTERADA`, com quem e quando, mas **sem os valores**: a trilha fica 5 anos
  (ADR 0022), e o dado sensível continuaria lá depois de a pessoa apagá-lo (o mesmo motivo do item 9 do ADR
  0046). O ADMIN abrir a identidade de um aluno vai para o registro de acessos (ADR 0031), como abrir o cadastro.
- **Quem preenche:** a própria pessoa, em "Meus dados", e o ADMIN, pelo que a pessoa informou no balcão. O aluno
  inativo (só de leitura, ADR 0029) consulta, mas não altera: pede ao ADMIN.
- **Banco:** a descrição só acompanha "outra" e é obrigatória nela (CHECK com `IS NOT DISTINCT FROM`, que não
  deixa passar a descrição solta com a opção nula). A aplicação não tem DELETE nessas tabelas; a linha sai com o
  cadastro (chave estrangeira em cascata, ADR 0043). A gravação trava o cadastro (`FOR SHARE`): uma exclusão no
  mesmo instante faz o pedido esperar e sair com 404, e não com 500 (o teste força essa corrida).

Mutantes equivalentes nas telas: mandar a descrição fora da opção "outra" (o campo nem existe na tela, e o
valor sai nulo do mesmo jeito).

### Boleto gerado no balcão (decisão 4)
Antes, só o aluno gerava o boleto, pelo app; no balcão, a recepção só registrava dinheiro, Pix ou cartão.
Agora a recepção e o ADMIN geram, na página do aluno, o boleto de uma cobrança em aberto
(`POST /api/pagamentos-online/balcao/boleto`), o mesmo do app (ADR 0034): o valor de hoje, o prazo do
vencimento (ou alguns dias, na vencida), devolve o que ainda vale e cancela na empresa o que venceu. Quem
gerou fica no pagamento (`gerado_por`). A tela mostra a linha digitável, o botão de copiar e o link do boleto
(quando a empresa manda um); o aluno também vê o boleto no app.

- O boleto não passa pelo caixa (V41): a cobrança fica paga quando a empresa avisa, como no app.
- O balcão gera para **qualquer** cobrança em aberto, como o recebimento no balcão; o limite de adiantar (só
  as vencidas ou a próxima) continua só no app. Decisão técnica: o balcão já recebe qualquer uma em dinheiro.
- Sem empresa, ou com o boleto desligado na configuração, o botão nem aparece (`GET
  /api/pagamentos-online/balcao`), e a API recusa (409).

### Notícias, comunicados e eventos como página inicial (decisão 6)
Módulo novo, `noticias`, sem porta pública por enquanto (ADR 0015). Tabelas `publicacoes` e `publicacao_links`
(V57). Cada publicação é uma notícia, um comunicado ou um evento, com título (até 150), texto (até 5.000) e até
10 links; só o evento tem quando (início obrigatório, fim opcional, não antes do início) e onde. Os anexos vêm
na parte seguinte.

- **Quem:** todos que têm login leem (`GET /api/publicacoes`, filtro por tipo, e `/proximos-eventos`); o ADMIN
  e a recepção publicam, editam e excluem. A página inicial de todos os perfis mostra o mural depois do
  "Olá"; a do aluno mantém antes o aviso de atraso e depois a matrícula.
- **Segurança:** o texto é texto simples, guardado como veio e mostrado como texto (o React escapa; nada de
  `innerHTML`). Os links só com `http://` ou `https://`, em minúsculas e com servidor, conferidos na aplicação e
  no banco (CHECK): um `javascript:` viraria código no navegador de quem clicasse. Abrem fora do app, com
  `rel="noopener noreferrer"`.
- **Trilha:** `PUBLICADA`, `EDITADA` e `EXCLUIDA`, com o retrato (o conteúdo é público, não é dado pessoal).
  A aplicação tem DELETE nas duas tabelas (exclusão e troca dos links), mas não TRUNCATE.
- **Hora do evento:** o campo da tela não tem fuso; a tela converte para a hora da academia, e não a do aparelho
  (testado com o aparelho em Tóquio).
- **Edição ao mesmo tempo:** a última vence (sem ETag, ao contrário dos cadastros do ADR 0009): é um texto de
  mural, editado por poucas pessoas, e a trilha guarda a versão anterior. Edição e exclusão juntas se esperam
  (trava na linha).

Mutantes equivalentes na tela: mandar data e local fora do evento, e filtrar os links vazios (os campos nem
existem fora do evento, e o link vazio é barrado pelo campo obrigatório).

### Anexos: fotos, vídeos e documentos (decisão 6)
**Onde ficam: no próprio banco**, em pedaços de até 1 MB (`publicacao_anexos` e `anexo_partes`, V58). A aplicação
roda com o disco só de leitura e o `/tmp` na memória (que conta no teto de 1 GB do container): um vídeo de 50 MB
não pode passar por ali. No banco, os anexos entram no backup cifrado que já existe (ADR 0041) e na restauração
que o e2e testa, sem volume nem script novos; saem junto com a publicação (cascata), sem arquivo órfão; e seguem
as permissões do usuário da aplicação. O custo é o banco e o backup crescerem com os anexos: por isso há um teto
de espaço total (`ANEXOS_ESPACO_TOTAL`, 5 GB por padrão) e a escolha de vídeo curto (o longo vai por link).

- **Envio:** `POST /api/publicacoes/{id}/anexos`, o arquivo cru no corpo e o nome em `X-Nome-Do-Arquivo`. O
  limite de corpo (64 KB na aplicação, 1 MB no Caddy) tem uma exceção só para essa rota (50 MB e 51 MB); o perfil
  é conferido antes de ler o corpo (`PerfilAntesDoCorpo`). O arquivo chega em pedaços de 1 MB, cada um gravado
  numa transação curta, com o anexo em `ENVIANDO`: um envio lento pela rede não segura uma conexão do banco
  (ADR 0020). No fim, numa transação que trava a publicação e reconta, vira `PRONTO`. Qualquer falha apaga o que
  chegou, e o envio interrompido (`ENVIANDO` há mais de 1 hora) é limpo a cada 15 minutos.
- **Tipo:** pelos primeiros bytes (JPEG, PNG, WebP, GIF, MP4, WebM, PDF), nunca pelo nome ou pelo tipo declarado.
  SVG e HTML ficam de fora (podem levar script). Tetos: foto 5 MB, PDF 10 MB, vídeo 50 MB; 10 por publicação; no
  máximo 3 envios ao mesmo tempo (503 acima disso: cada um usa 1 MB de memória). O espaço total é conferido a
  cada pedaço (envios simultâneos podem passar juntos do teto, no máximo pelo teto de envios ao mesmo tempo).
- **Leitura:** `<img>` e `<video>` não mandam o token. Cada publicação vem com o endereço de cada anexo
  **assinado** (HMAC-SHA256 com uma chave derivada do `JWT_SEGREDO`, sem configuração nova), válido até a virada
  da hora seguinte (1 a 2 horas; o endereço não muda dentro da hora, e o navegador aproveita o que baixou).
  Assinatura errada, prazo vencido ou anexo inexistente dão o mesmo 404. O tipo da resposta é o reconhecido; a
  foto e o vídeo abrem na página, o PDF vem como download, com `nosniff` e cache só do navegador. O conteúdo é
  lido em pedaços (1 MB por vez) e atende `Range` (o vídeo toca sem baixar tudo).
- **Trilha:** `ANEXO_ADICIONADO` e `ANEXO_EXCLUIDO` na publicação, com nome, tipo e tamanho.
- **Corrida:** a publicação excluída no meio de um envio leva o anexo (cascata), e o envio sai com 404, e não 500
  (o `AnexoLimitesTest` força a corrida com um envio parado no meio).

Ficou de fora: comprimir ou converter vídeo (pesado para o servidor; o celular já grava comprimido) e miniatura das
fotos (o navegador reduz na tela; com 5 MB por foto, o peso é aceitável para uma academia).

### Avisos de fatura por e-mail (decisão 7)
Módulo novo, `avisos`, sem porta pública. Ele usa duas portas novas: `CobrancasParaAvisos` (as faturas em aberto
por prazo, com o devido de hoje, as regras do atraso e a conferência com trava) e os contatos dos alunos
(`AlunosParaOutrosModulos.contatos`).

- **Quando:** a cada 10 minutos, a partir das 9h no fuso da academia; os do dia saem quando o servidor voltar, se
  ele estava desligado às 9h. Marcos: 5, 3 e 1 dia antes do prazo, no dia, e 1, 3 e 5 dias de atraso, só no dia
  exato (um dia inteiro com o servidor desligado não é recuperado depois).
- **Quem pagou nunca recebe:** para cada aviso, uma transação trava a fatura para leitura (`FOR SHARE`), confere que
  ela continua em aberto e reserva o aviso em `avisos_de_fatura` (V59), cuja chave (fatura, marco, canal) impede o
  aviso repetido, mesmo com o servidor reiniciando. O envio vem depois, fora da transação (ele vai para a rede). O
  único caso de aviso a quem pagou é um pagamento nas frações de segundo entre a reserva e o envio. O boleto pago
  ainda não confirmado pelo banco (até 3 dias úteis) não é conhecido pelo sistema: por isso o aviso de atraso diz
  para desconsiderar quem já pagou por boleto nos últimos dias.
- **Textos:** antes do vencimento, amigáveis ("passando para adiantar uma boa notícia: ainda dá tempo de pagar sem
  nenhum acréscimo"), dizendo também que o atraso aumenta o valor e bloqueia o acesso. Em atraso, o valor de hoje
  (com acréscimo e juros), o aumento e o bloqueio, com os números da configuração da cobrança (acréscimo, juros,
  dias para bloquear) e quantos dias faltam para o bloqueio.
- **Quem recebe:** o aluno com e-mail. Quem saiu da academia (inativo) recebe só os de atraso (mudou depois do
  diagnóstico; veja o fim). O aviso de fatura não se desliga (decisão 7).
- **Falha:** o servidor de e-mail fora do ar marca `FALHOU`, e a rodada seguinte tenta de novo, até 3 vezes no dia.
  Sem e-mail configurado, nada roda.

### SMS (decisão 9)
Porta `MensageiroSms` no `compartilhado`, como o `Correio`: `SMS_PROVEDOR=desligado` (o padrão) ou `simulado`. A
empresa (Zenvia, Twilio, AWS SNS...) ainda será escolhida pelo dono e entra como mais uma opção, numa classe que
implementa a porta. O simulado guarda o que mandaria na tabela `sms_simulados` (V60), para quem testa conferir, e
**não sobe com um `DOMINIO` de verdade** (como o pagamento simulado, ADR 0041): lá, o sistema acharia que avisou
quem nunca recebeu nada.

- **Avisos de fatura:** cada aviso vai também por SMS, com texto curto (até ~200 caracteres, começando por
  "Academia:", porque o número é desconhecido de quem recebe) e o mesmo conteúdo: valor, prazo, que o atraso aumenta o
  valor e bloqueia o acesso, e, em atraso, o valor de hoje e quantos dias faltam para o bloqueio.
- **Só celular:** 11 dígitos com o 9 depois do DDD; telefone fixo não recebe SMS.
- **Cada canal com a sua reserva** em `avisos_de_fatura`: o e-mail fora do ar não segura o SMS, e um não repete o
  outro. Os avisos rodam se houver e-mail ou SMS ligado.

### Avisos de eventos e comunicados, e desligar (decisão 8)
Ao publicar um evento ou comunicado, quem publica marca "avisar alunos e equipe por e-mail e SMS" (vem marcado; a
notícia não avisa, e a API recusa `avisar` nela). O módulo de notícias solta o evento `PublicacaoParaAvisar` (no
`compartilhado`), e o de avisos o ouve **depois do commit** (sem ciclo entre os módulos, e uma publicação desfeita não
avisa ninguém).

- **Fila:** o que foi publicado fica guardado como estava (`avisos_da_academia`, V61), e cada pessoa e canal entra em
  `envios_da_academia`, cuja chave impede o repetido. Uma tarefa manda 100 de cada vez, a cada minuto, fora de
  transação: milhares de alunos não seguram a publicação nem o banco. Falha tenta de novo, até 3 vezes.
- **Quem recebe:** alunos ativos (e-mail e, com SMS ligado, celular) e a equipe ativa (só e-mail: a equipe não tem
  telefone no sistema), menos quem desligou. O contato não é copiado para a fila: é lido do cadastro na hora do envio,
  e quem desligou, saiu ou ficou sem contato nesse meio tempo é cancelado.
- **Desligar:** em "Meus dados", para alunos e equipe (`GET`/`PUT /api/avisos/preferencias`, o id vem do token).
  Vale só para eventos e comunicados: o aviso de fatura não se desliga. O e-mail do aviso diz como desligar. O aluno
  inativo (só de leitura) consulta, mas não muda (e já não recebe).
- **Excluir a publicação** (evento `PublicacaoExcluida`) cancela o que ainda não saiu.
- **Limite conhecido:** a fila é montada logo depois do commit, numa transação nova (uma conexão a mais por um
  instante, ADR 0020); se ela falhar, a publicação fica sem aviso (o erro vai para o log). A fila não é apagada (cresce
  com alunos × publicações avisadas; para uma academia, milhares de linhas por ano).

### Correções do diagnóstico das funções novas
O diagnóstico de 30/10, numa instalação isolada e de fora, pelo Caddy, achou dois pontos técnicos, corrigidos aqui:

- **Memória dos downloads:** cada leitura de anexo trazia o pedaço inteiro de 1 MB para a memória, e no G1 (com região
  de 1 MB) isso é um objeto "gigante". 20 downloads simultâneos de um vídeo de 40 MB levaram o container a 958 MB de
  1 GB. Agora a leitura é em trechos de 256 KB (`substring`, que, com o pedaço sem compressão, lê só o trecho). Na mesma
  medida, ficou em 389 MB.
- **Quebra de linha no título:** o título, o local do evento e o nome do link aceitavam quebra de linha, e o título vira
  o assunto do e-mail do aviso. O teste com um servidor SMTP de verdade mostrou que o Jakarta Mail já neutraliza a
  quebra (um `Bcc:` injetado não vira cabeçalho). Mesmo assim, esses campos passam a ser de uma linha só (400), e o
  teste fica para avisar se uma troca de biblioteca mudar o comportamento.

## Decisões do dono depois do diagnóstico (30/10)

- **Aluno inativo recebe os avisos de atraso.** Antes, quem saiu da academia não recebia aviso nenhum, e a dívida ficava
  só com a recepção. Agora ele recebe os de atraso (1, 3 e 5 dias) da fatura que ficou em aberto, e não os lembretes de
  antes do vencimento nem o do dia, que são para quem continua treinando. O texto dele (e-mail e SMS) não fala do
  bloqueio do acesso, que não faz sentido para quem saiu, nem manda pagar pelo app: o login do inativo só consulta
  (ADR 0029) e não gera Pix nem boleto. Ele paga na recepção (decisão do dono de 31/10).
- **SMS desligado.** Os avisos saem por e-mail (as notificações do app ficam para uma etapa seguinte); o SMS
  continua atrás da porta, desligado por padrão (`SMS_PROVEDOR=desligado`), para o dia em que o dono quiser
  contratar uma empresa.
- **Boleto no balcão fica.** O dono chegou a pedir para tirar o boleto gerado pela recepção e pelo ADMIN, e mudou de
  ideia no mesmo dia: continua como na decisão 4.
- **Boleto simulado com tela (31/10).** No modo de teste, o boleto vinha só com a linha digitável, sem o endereço que a
  empresa de verdade manda (o PDF do boleto), e o link "Abrir o boleto" nunca aparecia. Agora o simulador manda o
  endereço de uma tela do próprio app (`/boleto-simulado/{id}`), como já fazia com o cartão (ADR 0037): ela abre numa
  aba nova, com o valor, o prazo, a linha digitável e o aviso de que o boleto não vale. Só existe com o provedor
  simulado (sem ele, 404); o aluno vê os dele, e a recepção e o ADMIN, os que geraram no balcão.
