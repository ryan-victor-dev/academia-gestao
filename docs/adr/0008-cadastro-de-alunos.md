# ADR 0008 — Cadastro de alunos: CPF, inativação e acesso por perfil

- **Status:** Aceito
- **Data:** 2026-10-16

## Contexto

O cadastro de alunos é a base das próximas etapas (matrículas, cobrança,
treinos). Ele guarda dado pessoal (CPF, telefone, nascimento), é usado por
perfis diferentes no dia a dia da academia e não pode perder histórico quando
um aluno sai.

## Decisão

### Modelo
- `Aluno` é entidade JPA no mesmo estilo do `Usuario`: sem setters, criada
  pela fábrica `Aluno.novo(...)` e alterada só por `atualizar`, `inativar` e
  `reativar`. Todos os campos são validados antes de qualquer um mudar.
- Não separei domínio e entidade como na `Mensalidade` (ADR 0004): lá existe
  uma máquina de estados que precisa ser blindada; aqui as regras são de
  formato e cabem na própria entidade sem perder clareza.
- `Cpf` é Java puro: normaliza, formata e confere os dígitos verificadores,
  recusando sequências repetidas (111.111.111-11). A mesma classe é usada na
  entidade e na validação da requisição (`@CpfValido`), então a regra vive num
  lugar só.
- E-mail vai para minúsculas; telefone é gravado só com dígitos (DDD +
  número, 10 ou 11 dígitos).

### Banco
- O CPF é gravado com 11 dígitos, sem pontuação. O banco tem `UNIQUE` e um
  `CHECK` de formato: mesmo um `INSERT` feito fora da aplicação não grava CPF
  repetido nem malformado.
- **Aluno que sai é inativado, não apagado** (coluna `ativo`, migration V4):
  matrículas e mensalidades vão continuar apontando para ele.

### API e permissões

| Operação | ADMIN | RECEPCAO | INSTRUTOR | ALUNO |
|---|:-:|:-:|:-:|:-:|
| Listar / consultar | ✓ | ✓ | ✓ | |
| Cadastrar / editar / inativar / reativar | ✓ | ✓ | | |
| Ver o próprio cadastro (`/api/alunos/me`) | | | | ✓ |

- O instrutor consulta porque precisa ver o aluno para montar a ficha de treino.
- O aluno só acessa `GET /api/alunos/me`. O id vem da claim `alunoId` do
  token, nunca da URL: não existe rota em que trocar um id na URL mostre o
  cadastro de outra pessoa (o ataque conhecido como IDOR).
- A claim `alunoId` é colocada no login, que já conhece o vínculo
  usuário ↔ aluno. Assim o módulo `alunos` não consulta tabelas do `auth`
  (ADR 0002). Os nomes das claims ficam em `ClaimsDoToken`.
- Aluno inativo continua vendo os próprios dados: é direito de acesso dele
  (LGPD, art. 18).
- `PUT` substitui todos os dados editáveis; campo opcional ausente fica vazio.

### Listagem
- Uma caixa de busca só: se o texto tem apenas dígitos, pontos e traço,
  procura pelo começo do CPF; senão, por qualquer parte do nome, sem
  diferenciar maiúsculas. Os curingas do LIKE (`%`, `_`) são escapados.
- Só ativos por padrão (`incluirInativos=true` traz todos).
- Paginação com `pagina`/`tamanho`, máximo de 100 por página, ordem fixa por
  nome (e id, para desempate estável).
- Resposta num formato próprio (`Pagina`), não no `Page` do Spring Data, para o
  JSON não mudar se a biblioteca mudar.

### Erros
- **400** com a lista de campos inválidos (`erros[].campo` e `mensagem`),
  inclusive para parâmetros da URL; **404** para aluno inexistente; **409**
  para CPF repetido. Todos em ProblemDetail.
- Dois cadastros simultâneos com o mesmo CPF passam pela checagem
  `existsByCpf`; o `UNIQUE` do banco barra o segundo, e o erro também vira 409.

## Consequências

- O cadastro está pronto para receber matrículas, sem risco de apagar
  histórico.
- As permissões estão documentadas como teste (`PermissoesDeAlunosTest`):
  mudar uma regra sem querer quebra o build e aponta qual combinação mudou.

## Pendências

- **Busca sem acento:** hoje "Jose" não encontra "José". Resolver com a
  extensão `unaccent` do PostgreSQL.
- **Login do aluno:** ainda não há endpoint para criar o acesso de um aluno
  (decidido que será uma ação separada do cadastro).
- **Aluno inativado com login:** quando existir o login de aluno, decidir se
  inativar o aluno também desativa o acesso dele.
- **Edição simultânea:** duas pessoas editando o mesmo aluno, a última
  sobrescreve a outra sem aviso. Se virar problema, usar travamento otimista
  (`@Version`) e responder 409.
- **Minimização de dados:** o instrutor vê o CPF, que ele não precisa para
  montar treino. Avaliar uma resposta reduzida por perfil.
