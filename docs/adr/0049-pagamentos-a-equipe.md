# ADR 0049 — Pagamentos à equipe: o controle da folha

- **Status:** Aceito (em andamento: parte 1)
- **Data:** 2026-10-30

## Contexto

Em 30/10, o dono pediu uma opção para o ADMIN "fechar a folha salarial" dos funcionários, e perguntou como se paga
um CLT e um MEI. Resumo do que foi explicado a ele:

- **CLT** é empregado: salário até o 5º dia útil do mês seguinte, com holerite; INSS e imposto de renda descontados;
  FGTS (8%) e, fora do Simples Nacional, INSS patronal por conta da academia; férias com 1/3, 13º, rescisão; tudo
  declarado no eSocial todo mês, com certificado digital. Em academia pequena, quem faz é o contador.
- **MEI** é uma empresa que presta serviço: a academia paga o combinado contra a nota fiscal, sem desconto, FGTS,
  férias, 13º nem eSocial. Dois riscos a conversar com o contador: o vínculo disfarçado (horário fixo, chefe e
  salário fixo fazem dele um empregado) e se a atividade é permitida ao MEI (educação física é profissão
  regulamentada).

Três caminhos foram apresentados: (A) só o controle do que foi combinado e pago; (B) A mais comissões; (C) a folha
CLT completa, com os cálculos. A C foi desaconselhada: as regras e faixas mudam todo ano, um cálculo errado vira
passivo trabalhista, e sem o envio ao eSocial ela não substitui o contador.

## Decisões do dono

1. **Opção A:** só o controle dos pagamentos. O sistema não calcula imposto, encargo, férias nem 13º; o valor pago
   é sempre digitado (para o CLT, o líquido do holerite do contador; para o MEI, o da nota).
2. **Só o ADMIN** vê e lança. Cada consulta vai para o registro de acessos (ADR 0031).
3. **Vários pagamentos por pessoa no mês**, cada um com tipo (adiantamento, salário, 13º, férias, rescisão, serviço do
   MEI, bônus, outro), data, valor e forma.
4. **Fechar o mês trava:** nada daquele mês muda nem some; um erro se corrige com um ajuste, com motivo, num mês
   aberto (como o estorno das cobranças).
5. **Comprovante anexo opcional** (PDF ou imagem), que pode ser anexado depois.
6. **Pessoa sem login:** quem trabalha na academia sem usar o sistema (limpeza, manutenção, aula coletiva) é
   cadastrado só para a folha, com nome, CPF e vínculo, sem acesso nenhum. Quem tem login aparece sozinho.
7. **Nunca passa pelo caixa da recepção:** o pagamento à equipe é uma saída separada (Pix, transferência ou dinheiro
   do cofre); o caixa do dia e a conferência às cegas (ADR 0031) não mudam.
8. **Vínculo e valor combinado** de cada pessoa ficam guardados, para o fechamento comparar o combinado com o pago e
   destacar quem ainda não recebeu.

## Partes

1. Pessoas da folha: vínculo, valor combinado e a pessoa sem login (este registro).
2. Lançar os pagamentos, com o mês de referência e o tipo.
3. A tela do mês (combinado × pago) e o fechamento, que trava o mês.
4. Ajustes de um mês fechado.
5. Comprovantes anexos.
6. Documentação, e2e e diagnóstico do conjunto.

O mês de referência (competência) e a data do pagamento são coisas diferentes: o salário de outubro sai em 5 de
novembro e entra na folha de outubro. É a competência que se fecha.

## Parte 1: pessoas da folha

Um módulo novo, `folha`, no mesmo molde das notícias (SQL com `JdbcTemplate`, regras em records de Java puro). Ninguém
de fora o usa (`ArquiteturaTest`), e ele não depende de nenhum outro módulo: o nome, o CPF, o perfil e a situação de
quem tem login vêm da tabela `usuarios` no próprio SQL, como as notícias já faziam com o nome do autor.

**Tabela `pessoas_da_folha` (V64).** Uma linha por pessoa: ou o login (`usuario_id`, sem nome nem CPF, que são os do
login), ou quem não tem login (nome e CPF, sem `usuario_id`). O banco barra a mistura (`ck_pessoas_da_folha_quem`), o
nome em branco, o CPF fora do formato e o valor zero ou negativo. O vínculo é `CLT`, `MEI` ou `AUTONOMO`; o valor
combinado é opcional (nulo: sem valor fixo, como quem recebe por aula). A situação de quem tem login é a do login (a
coluna `ativo` só vale para quem não tem).

- **Quem tem login aparece sozinho:** a lista junta a equipe (`usuarios` sem os logins de alunos) com as linhas da
  folha. Quem ainda não tem linha vem sem `id` e sem vínculo, e a tela pede "Definir vínculo". Login de aluno nunca
  entra, nem pedindo (404).
- **Uma linha por login:** quem barra o repetido é o índice único de `usuario_id`, também na corrida (dois pedidos ao
  mesmo tempo: um 201, o outro 409). Uma checagem antes de gravar daria a mesma resposta (a mutação mostrou que ela não
  mudava nada) e foi tirada. Quem inclui lê o login com `FOR SHARE`: a exclusão do login (ADR 0043) espera, e não dá 500 na chave
  estrangeira no meio.
- **CPF:** o de quem não tem login é obrigatório, válido e não se repete na folha (índice único; na corrida, um 201 e
  um 409). O CPF de alguém da equipe com login é recusado (409): essa pessoa entra pelo login, e não de novo.
- **Quem tem login muda só o combinado** (`PUT` sem nome, CPF nem situação; mandar dá 400): o resto muda na aba
  Equipe. Quem não tem muda tudo, inclusive a situação (`ativo`; sem ele, fica como estava). Sem excluir: quem sai é
  inativado (a aplicação não tem DELETE na tabela, e os pagamentos da parte 2 vão apontar para ela).
- **Versão:** o `PUT` exige `If-Match` (428 sem ele, 412 com a versão velha), como os planos e os alunos.
- **Trilha:** `PESSOA_DA_FOLHA`, com `INCLUIDA` e `EDITADA`, inclusive o valor combinado (a trilha também é só do
  ADMIN, ADR 0031).
- **Excluir um login (ADR 0043):** o login que está na folha tem histórico e não se exclui (a chave estrangeira dá o
  mesmo 409 de sempre: desative).

**Registro de acessos (decisão 2):** cada consulta (a lista e uma pessoa) grava uma linha `CONSULTA_FOLHA`, com quem e
de onde, sem conta digitada nem aluno, como a abertura do relatório de idades (ADR 0048, V63, cujas regras do banco a
V64 estende). Uma linha por consulta, e não uma por pessoa listada. A resposta vai com `Cache-Control: no-store`.

**Rotas** (só o ADMIN): `GET /api/folha/pessoas` (`incluirInativos`), `GET /api/folha/pessoas/{id}` (com `ETag`),
`POST /api/folha/pessoas` (com `usuarioId`, ou com `nome` e `cpf`; mais `vinculo` e `valorCombinado`) e
`PUT /api/folha/pessoas/{id}`.

**Tela:** "Folha" no menu do ADMIN, com a seção "Pessoas": o perfil (ou "Sem login"), o vínculo, o valor por mês (ou
"sem valor fixo"), o aviso de quantas pessoas da equipe ainda não têm vínculo, e os formulários de definir o vínculo,
editar e cadastrar a pessoa sem login.
