// Conversa com a API e guarda a sessão (ADR 0033).
//
// O token de acesso (15 min) fica só nesta variável, na memória da página: nunca em
// localStorage, onde qualquer script injetado o leria. Quem mantém a pessoa conectada é o
// cookie da sessão (HttpOnly, que nenhum script lê): ao abrir a página, ou quando o token
// vence, POST /api/auth/renovar troca o cookie por um token novo.

export type Perfil = 'ADMIN' | 'RECEPCAO' | 'INSTRUTOR' | 'ALUNO';

export interface Usuario {
  id: string;
  nome: string;
  email: string;
  /** Só da equipe, sem pontuação; nulo em quem foi cadastrado antes do ADR 0047 e no login de aluno. */
  cpf?: string | null;
  perfil: Perfil;
  ativo: boolean;
  precisaTrocarSenha: boolean;
  /** Aluno e equipe confirmam o e-mail no primeiro acesso, com um código (ADR 0038). Só no /api/me. */
  precisaConfirmarEmail?: boolean;
  somenteLeitura: boolean;
}

interface RespostaDeLogin {
  token: string;
  expiraEm: string;
  precisaTrocarSenha: boolean;
  sessaoExpiraEm: string;
}

/** Erro devolvido pela API (ProblemDetail, RFC 9457) ou falha de rede (status 0). */
export class ErroDaApi extends Error {
  readonly status: number;
  readonly erros: Record<string, string>;
  /** Segundos até poder tentar de novo (o Retry-After do 429 e do 503), quando a API diz. */
  readonly esperar: number | null;
  /** O título do problema ("Muitas tentativas"), mais curto que a explicação. */
  readonly titulo: string | null;

  constructor(status: number, mensagem: string, erros: Record<string, string> = {},
              { esperar = null, titulo = null }: { esperar?: number | null; titulo?: string | null } = {}) {
    super(mensagem);
    this.name = 'ErroDaApi';
    this.status = status;
    this.erros = erros;
    this.esperar = esperar;
    this.titulo = titulo;
  }
}

/** O Retry-After em segundos; a forma de data (que a API não usa) fica de fora. */
function segundosDe(retryAfter: string | null): number | null {
  return retryAfter !== null && /^\d+$/.test(retryAfter) && Number(retryAfter) > 0 ? Number(retryAfter) : null;
}

let token: string | null = null;
let renovacaoEmAndamento: Promise<boolean> | null = null;

async function erroDe(resposta: Response): Promise<ErroDaApi> {
  try {
    const problema = await resposta.json();
    // Erros de validação vêm como [{campo, mensagem}]; viram {campo: mensagem}.
    const erros = Object.fromEntries(
      ((problema.erros ?? []) as Array<{ campo: string; mensagem: string }>).map((e) => [e.campo, e.mensagem]));
    return new ErroDaApi(resposta.status, problema.detail ?? problema.title ?? 'Erro inesperado.', erros,
      { esperar: segundosDe(resposta.headers.get('Retry-After')), titulo: problema.title ?? null });
  } catch {
    return new ErroDaApi(resposta.status, 'Erro inesperado.', {},
      { esperar: segundosDe(resposta.headers.get('Retry-After')) });
  }
}

async function buscar(caminho: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(caminho, init);
  } catch {
    throw new ErroDaApi(0, 'Sem conexão com o servidor. Confira a internet e tente de novo.');
  }
}

/** Entra com e-mail e senha. O cookie da sessão chega junto, guardado pelo navegador. */
export async function entrar(email: string, senha: string): Promise<void> {
  const resposta = await buscar('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, senha }),
  });
  if (!resposta.ok) {
    throw await erroDe(resposta);
  }
  token = ((await resposta.json()) as RespostaDeLogin).token;
}

/** POST sem login (o "esqueci a senha" e o primeiro acesso, ADRs 0038 e 0039): nem token nem renovação. */
async function publico(caminho: string, corpo: unknown): Promise<void> {
  const resposta = await buscar(caminho, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(corpo),
  });
  if (!resposta.ok) {
    throw await erroDe(resposta);
  }
}

/**
 * O que a tela de login oferece: o "esqueci a senha" e o primeiro acesso do aluno só com o e-mail
 * ligado. Falhou (sem conexão, servidor subindo): `null`, para a tela não confundir com "desligado".
 */
export async function opcoesDoLogin(): Promise<OpcoesDoLogin | null> {
  try {
    const resposta = await buscar('/api/auth/opcoes', { method: 'GET' });
    return resposta.ok ? { esqueciSenha: false, primeiroAcesso: false, ...(await resposta.json()) } : null;
  } catch {
    return null;
  }
}

export type OpcoesDoLogin = { esqueciSenha: boolean; primeiroAcesso: boolean };

/** Pede o código da nova senha. A resposta é sempre a mesma, exista o e-mail ou não. */
export function pedirCodigoDeSenha(email: string): Promise<void> {
  return publico('/api/auth/esqueci-senha', { email });
}

/** Com o código do e-mail, cria a senha nova (as sessões abertas caem). */
export function redefinirSenha(email: string, codigo: string, novaSenha: string): Promise<void> {
  return publico('/api/auth/redefinir-senha', { email, codigo, novaSenha });
}

/** Primeiro acesso do aluno (ADR 0039): pede o código. A resposta é sempre a mesma, seja aluno ou não. */
export function pedirCodigoDoPrimeiroAcesso(cpf: string, email: string): Promise<void> {
  return publico('/api/auth/primeiro-acesso', { cpf, email });
}

/** Com o código do e-mail, cria o login do aluno, já com o e-mail confirmado. */
export function criarSenhaDoPrimeiroAcesso(cpf: string, email: string, codigo: string, senha: string): Promise<void> {
  return publico('/api/auth/primeiro-acesso/senha', { cpf, email, codigo, senha });
}

/**
 * Troca o cookie da sessão por um token novo. Chamadas simultâneas (várias requisições que
 * receberam 401 juntas) compartilham uma renovação só: cada renovação troca o segredo do
 * cookie, e renovar de novo com o segredo antigo, passada a folga, derruba a sessão.
 */
export function renovar(): Promise<boolean> {
  renovacaoEmAndamento ??= (async () => {
    try {
      // Sem o cabeçalho Authorization: a sessão se identifica pelo cookie.
      const resposta = await buscar('/api/auth/renovar', { method: 'POST' });
      token = resposta.ok ? ((await resposta.json()) as RespostaDeLogin).token : null;
      return token !== null;
    } catch {
      return false;
    } finally {
      renovacaoEmAndamento = null;
    }
  })();
  return renovacaoEmAndamento;
}

/**
 * Troca a senha do próprio usuário. A API devolve um token novo (sem a restrição da senha
 * temporária) e abre uma sessão nova, com cookie novo: a antiga deixa de renovar.
 */
export async function trocarSenha(senhaAtual: string, novaSenha: string): Promise<void> {
  const resposta = await chamar<RespostaDeLogin>('/api/auth/trocar-senha', {
    metodo: 'POST',
    corpo: { senhaAtual, novaSenha },
  });
  token = resposta.token;
}

/** Manda (de novo) o código de confirmação para o e-mail do login (ADR 0038). */
export function enviarCodigoDeEmail(): Promise<void> {
  return chamar<void>('/api/auth/email/codigo', { metodo: 'POST' });
}

/** O e-mail estava errado: corrige e manda o código para o novo. */
export function corrigirEmail(email: string): Promise<void> {
  return chamar<void>('/api/auth/email', { metodo: 'PUT', corpo: { email } });
}

/** Confirma o e-mail com o código e renova o token, que sai sem a restrição. */
export async function confirmarEmail(codigo: string): Promise<void> {
  await chamar<void>('/api/auth/email/confirmar', { metodo: 'POST', corpo: { codigo } });
  await renovar();
}

/** Encerra a sessão no servidor e esquece o token. Falha de rede não impede sair da tela. */
export async function sair(): Promise<void> {
  token = null;
  try {
    await fetch('/api/auth/sair', { method: 'POST' });
  } catch {
    // Sem rede: a sessão vence sozinha no prazo; a tela já saiu.
  }
}

export interface Opcoes {
  metodo?: string;
  corpo?: unknown;
  /** A versão lida (ETag), mandada no If-Match: se outra pessoa alterou antes, a API dá 412. */
  versao?: string;
}

/**
 * Chama a API com o token. Se ele venceu (401), renova uma vez e repete: o 401 vem do filtro
 * de segurança, antes de a rota fazer qualquer coisa, então repetir é seguro mesmo num POST.
 */
async function requisitar(caminho: string, opcoes: Opcoes): Promise<Response> {
  if (token === null && !(await renovar())) {
    throw new ErroDaApi(401, 'Sessão encerrada: entre de novo.');
  }
  const enviar = () =>
    buscar(caminho, {
      method: opcoes.metodo ?? 'GET',
      headers: {
        Authorization: `Bearer ${token}`,
        ...(opcoes.corpo === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(opcoes.versao === undefined ? {} : { 'If-Match': opcoes.versao }),
      },
      body: opcoes.corpo === undefined ? undefined : JSON.stringify(opcoes.corpo),
    });

  let resposta = await enviar();
  if (resposta.status === 401) {
    if (!(await renovar())) {
      throw new ErroDaApi(401, 'Sessão encerrada: entre de novo.');
    }
    resposta = await enviar();
  }
  if (!resposta.ok) {
    throw await erroDe(resposta);
  }
  return resposta;
}

export async function chamar<T>(caminho: string, opcoes: Opcoes = {}): Promise<T> {
  const resposta = await requisitar(caminho, opcoes);
  return (resposta.status === 204 ? undefined : await resposta.json()) as T;
}

/** Como {@link chamar}, devolvendo também a versão (ETag) para uma alteração depois. */
export async function chamarComVersao<T>(caminho: string, opcoes: Opcoes = {}): Promise<{ dados: T; versao: string | null }> {
  const resposta = await requisitar(caminho, opcoes);
  return { dados: (await resposta.json()) as T, versao: resposta.headers.get('ETag') };
}

/** Só para os testes: volta ao estado de página recém-aberta. */
export function esquecerSessaoParaTestes(): void {
  token = null;
  renovacaoEmAndamento = null;
}
