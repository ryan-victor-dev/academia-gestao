# ADR 0009 — Login do aluno, senha temporária, edição simultânea e visão do instrutor

- **Status:** Aceito
- **Data:** 2026-10-16

## Contexto

O ADR 0008 deixou quatro pendências no cadastro de alunos: não havia como criar
o login do aluno, não estava definido o que acontece com esse login quando o
aluno é inativado, duas pessoas editando o mesmo aluno perdiam dados sem aviso,
e o instrutor via CPF, e-mail e telefone sem precisar deles.

## Decisão

### Senha temporária e troca obrigatória
- Um login pode nascer com **senha temporária** (`usuarios.precisa_trocar_senha`,
  migration V6). O login devolve `precisaTrocarSenha: true` e o token leva a
  claim `trocarSenha`.
- Esse token **não ganha o perfil** (`ROLE_...`), só a autoridade
  `SENHA_TEMPORARIA`. Assim nenhuma regra `@PreAuthorize` deixa passar, nem as
  que forem escritas no futuro.
- Segunda camada no filtro: com senha temporária, só
  `POST /api/auth/trocar-senha` e `GET /api/me` são aceitos; o resto devolve
  403 "Troca de senha obrigatória".
- `POST /api/auth/trocar-senha` (qualquer usuário logado) confere a senha
  atual, aplica a política (12 caracteres a 72 bytes, diferente da atual) e
  devolve um token novo, sem a restrição.

### Criar o login do aluno
- `POST /api/alunos/{id}/login`, por ADMIN ou RECEPCAO. Usa o e-mail do
  cadastro do aluno, que precisa existir (400), e o aluno precisa estar ativo
  (409). Aluno que já tem login ou e-mail já usado: 409.
- O sistema gera a senha temporária: 16 caracteres em 4 grupos
  (`xxxx-xxxx-xxxx-xxxx`), sem caracteres que se confundem (0/O, 1/l/I), com
  `SecureRandom` (~93 bits). Ela aparece **uma única vez**, na resposta, com
  `Cache-Control: no-store`; no banco fica só o hash, e ela não vai para o log.
  A recepção passa a senha ao aluno, que é obrigado a trocá-la no primeiro acesso.
  Ninguém da equipe conhece a senha final.
- O módulo `alunos` chama o `auth` só pela classe pública `LoginsDeAlunos`;
  `Usuario` e o repositório continuam internos. A dependência é numa direção só
  (`alunos → auth`), sem ciclo.

### Login acompanha o aluno
- Inativar o aluno desativa o login dele; reativar o aluno reativa o login. As
  duas mudanças acontecem na mesma transação.
- Um token emitido antes da inativação continuava válido até expirar (até 15
  minutos, ADR 0007). Desde o ADR 0010, ele é recusado na hora.

### Edição simultânea: ETag e If-Match (RFC 9110)
- `alunos.versao` (migration V7) mapeada com `@Version`: travamento otimista.
- GET, POST, PUT, inativar e reativar devolvem `ETag: "<versão>"`.
- `PUT /api/alunos/{id}` **exige** `If-Match`:
  - sem o cabeçalho: **428**;
  - versão diferente da atual: **412**, e nada é alterado;
  - `*`: sobrescreve de propósito (o cliente declara que quer isso);
  - ETag fraco (`W/"..."`) nunca é aceito (If-Match usa comparação forte).
- Duas edições simultâneas com o mesmo `If-Match` passam pela conferência, mas
  o `UPDATE ... WHERE versao = ?` só deixa uma gravar; a outra também vira 412.
- Inativar e reativar também mudam a versão: quem carregou o aluno antes
  precisa recarregar para editar.

### Visão do instrutor
- Para o INSTRUTOR, `GET /api/alunos` e `GET /api/alunos/{id}` devolvem só
  `id`, `nome`, `dataNascimento`, `observacoes` e `ativo` (LGPD, minimização).
- Para ele, a busca é só por nome: buscar por CPF revelaria, pelo resultado, de
  quem é cada CPF.

## Alternativas consideradas

- **Recepção define a senha inicial:** a equipe conheceria a senha do aluno até
  a troca e tenderia a reutilizar senhas fáceis.
- **Link de criação de senha por e-mail:** a melhor experiência, mas exige
  envio de e-mail (SMTP), que o projeto ainda não tem.
- **Campo `versao` no JSON em vez de ETag:** mais simples para o front-end,
  mas fora do padrão HTTP; 412/428 existem exatamente para isso.
- **If-Match opcional:** compatível, mas quem não mandasse continuaria
  sobrescrevendo; não resolveria o problema.

## Consequências

- O front-end precisa guardar o `ETag` do GET e mandá-lo no PUT. Quando houver
  front-end em outro domínio, o CORS precisa expor o cabeçalho
  (`Access-Control-Expose-Headers: ETag`).
- Todas as regras acima têm testes, e cada teste foi validado quebrando a regra
  de propósito e confirmando que ele falha.

## Pendências

As duas pendências registradas originalmente aqui ("esqueci minha senha" e
revogação imediata de tokens) foram resolvidas no
[ADR 0010](0010-revogacao-de-tokens-e-redefinicao-de-senha.md).
