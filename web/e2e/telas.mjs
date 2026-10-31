// Ponta a ponta pelas telas (ADR 0033), num Chromium de verdade, na instalação de teste do
// scripts/e2e.sh (roda depois do e2e.py, na pasta da instalação). Os quatro perfis trabalham
// juntos: o ADMIN monta a equipe e o plano, a recepção cadastra, matricula e recebe, o professor
// monta a ficha e o aluno vê tudo no celular. Também confere o PWA (manifesto, service worker,
// abrir sem internet sem guardar nada da API).
//
// Uso (na pasta da instalação): node <raiz>/web/e2e/telas.mjs https://localhost:<porta>
// Requer o Chromium do Playwright: cd web && npx playwright install chromium
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';

const URL = process.argv[2];
let ok = 0;
let falhas = 0;
const erros = [];

function confere(descricao, condicao, detalhe = '') {
  if (condicao) ok++; else falhas++;
  console.log(`${condicao ? '  ok   ' : '  FALHA'} ${descricao}${condicao ? '' : `  -> ${detalhe}`}`);
}
async function passo(descricao, fn) {
  try {
    const r = await fn();
    confere(descricao, r === true || r === undefined, typeof r === 'string' ? r : JSON.stringify(r));
  } catch (e) {
    confere(descricao, false, String(e).split('\n')[0]);
  }
}
const secao = (nome) => console.log(`\n== ${nome}`);

function cpfValido() {
  const b = Array.from({ length: 9 }, () => Math.floor(Math.random() * 10));
  const dv = (d) => { const r = d.reduce((s, v, i) => s + v * (d.length + 1 - i), 0) % 11; return r < 2 ? 0 : 11 - r; };
  const d1 = dv(b); const d2 = dv([...b, d1]);
  return [...b, d1, d2].join('');
}

// A instalação de teste usa o certificado de desenvolvimento do Caddy (CA interna). O Chromium
// recusa registrar service worker com erro de certificado, mesmo com ignoreHTTPSErrors; com um
// domínio de verdade (Let's Encrypt), isso não acontece.
const navegador = await chromium.launch({ args: ['--ignore-certificate-errors'] });
async function novaPagina(opcoes = {}) {
  const contexto = await navegador.newContext({ ignoreHTTPSErrors: true, ...opcoes });
  const pagina = await contexto.newPage();
  pagina.on('console', (m) => { if (m.type() === 'error') erros.push(m.text()); });
  pagina.on('pageerror', (e) => erros.push(String(e)));
  return pagina;
}
async function entrar(p, email, senha) {
  await p.goto(URL);
  await p.getByLabel('E-mail').fill(email);
  await p.getByLabel('Senha', { exact: true }).fill(senha);
  await p.getByRole('button', { name: 'Entrar' }).click();
}
/** Primeiro acesso: troca a senha temporária pela definitiva. */
async function criarSenha(p, temporaria, definitiva) {
  await p.getByRole('heading', { name: 'Crie sua senha' }).waitFor();
  await p.getByLabel('Senha atual').fill(temporaria);
  await p.getByLabel(/Nova senha/).fill(definitiva);
  await p.getByLabel('Repita a nova senha').fill(definitiva);
  await p.getByRole('button', { name: 'Salvar nova senha' }).click();
}
const menu = (p, nome) => p.getByRole('navigation', { name: 'Menu' }).getByRole('link', { name: nome }).click();
const senhaNaTela = async (p) => (await p.locator('.credenciais code').nth(1).textContent()).trim();

try {
  secao('ADMIN: primeiro acesso, equipe e plano');
  // O e2e.py deixou o ADMIN recuperado com senha temporária; aqui, outra recuperação (a senha
  // aparece uma vez, na saída do comando).
  const saida = execFileSync('docker', ['compose', 'run', '--rm', 'app', '--recuperar-admin=admin@academia.local'],
    { stdio: ['ignore', 'pipe', 'pipe'] }).toString();
  const temporariaAdmin = saida.split('\n').find((l) => l.includes('Senha temporária do ADMIN')).split(': ').pop().trim();
  const adm = await novaPagina();
  await entrar(adm, 'admin@academia.local', temporariaAdmin);
  await criarSenha(adm, temporariaAdmin, 'senha-das-telas-admin-1');
  await passo('ADMIN entra com o menu completo', async () => {
    await adm.getByRole('link', { name: 'Auditoria' }).first().waitFor();
  });

  await menu(adm, 'Equipe');
  // O CPF (ADR 0047) e a data de nascimento (ADR 0048) são obrigatórios no login novo da equipe.
  const criarLogin = async (nome, email, perfil, cpf) => {
    await adm.getByRole('button', { name: 'Novo login' }).click();
    await adm.getByLabel('Nome', { exact: true }).fill(nome);
    await adm.getByLabel('E-mail (é o login)').fill(email);
    await adm.getByLabel('CPF', { exact: true }).fill(cpf);
    await adm.getByLabel('Data de nascimento', { exact: true }).fill('1990-05-10');
    await adm.getByLabel('Perfil', { exact: true }).selectOption(perfil);
    await adm.getByRole('button', { name: 'Criar' }).click();
    const senha = await senhaNaTela(adm);
    await adm.getByRole('button', { name: 'Já passei, esconder' }).click();
    return senha;
  };
  const temporariaRec = await criarLogin('Rita das Telas', 'rita.telas@academia.local', 'RECEPCAO', cpfValido());
  const temporariaIns = await criarLogin('Ivo das Telas', 'ivo.telas@academia.local', 'INSTRUTOR', cpfValido());
  confere('dois logins da equipe criados', temporariaRec.length >= 8 && temporariaIns.length >= 8);

  await menu(adm, 'Planos');
  await adm.getByRole('button', { name: 'Novo plano' }).click();
  await adm.getByLabel('Nome', { exact: true }).fill('Plano das Telas');
  await adm.getByLabel('Valor por mês (R$)').fill('100');
  await adm.getByLabel('Duração (meses)').fill('3');
  await adm.getByRole('button', { name: 'Salvar' }).click();
  await passo('plano criado', async () => { await adm.locator('.cartao-item', { hasText: 'Plano das Telas' }).waitFor(); });

  // O caixa de hoje pode ter sido fechado pelo e2e.py: o ADMIN reabre para a recepção receber.
  await menu(adm, 'Caixa');
  await adm.locator('h1').waitFor();
  await adm.waitForTimeout(500);
  if (await adm.getByRole('button', { name: 'Reabrir o dia' }).count()) {
    await adm.getByRole('button', { name: 'Reabrir o dia' }).click();
    await adm.getByLabel('Motivo', { exact: true }).fill('teste das telas');
    await adm.getByRole('button', { name: 'Reabrir', exact: true }).click();
    await adm.getByText('Aberto', { exact: true }).waitFor();
  }

  secao('recepção: aluno, matrícula, caixa e acesso ao app');
  const rec = await novaPagina();
  await entrar(rec, 'rita.telas@academia.local', temporariaRec);
  await criarSenha(rec, temporariaRec, 'senha-das-telas-rita-1');
  await menu(rec, 'Caixa');
  await rec.locator('h1').waitFor();
  await rec.waitForTimeout(500);
  if (await rec.getByRole('button', { name: 'Abrir caixa' }).count()) {
    await rec.getByLabel('Troco na gaveta (R$)').fill('50');
    await rec.getByRole('button', { name: 'Abrir caixa' }).click();
  }
  await passo('caixa de hoje aberto', async () => { await rec.getByText('Aberto', { exact: true }).waitFor(); });

  await menu(rec, 'Alunos');
  await rec.getByRole('link', { name: 'Novo aluno' }).click();
  await rec.getByRole('heading', { name: 'Novo aluno' }).waitFor();
  await rec.getByLabel('Nome', { exact: true }).fill('Ana das Telas');
  await rec.getByLabel('CPF', { exact: true }).fill(cpfValido());
  await rec.getByLabel('E-mail', { exact: true }).fill('ana.telas@academia.local');
  // A data de nascimento é obrigatória no cadastro novo (ADR 0048).
  await rec.getByLabel('Nascimento', { exact: true }).fill('1995-03-10');
  await rec.getByRole('button', { name: 'Cadastrar' }).click();
  await rec.getByRole('heading', { name: 'Ana das Telas' }).waitFor();
  const alunoId = new globalThis.URL(rec.url()).pathname.split('/').pop();
  await rec.getByRole('button', { name: 'Nova matrícula' }).click();
  const plano = rec.getByLabel('Plano', { exact: true });
  await plano.selectOption(await plano.locator('option', { hasText: 'Plano das Telas' }).getAttribute('value'));
  await rec.getByRole('button', { name: 'Salvar' }).click();
  await passo('matrícula vigente e o aluno pode treinar', async () => {
    await rec.getByText('Vigente', { exact: true }).waitFor();
    await rec.getByText('Pode treinar', { exact: true }).waitFor();
  });
  const mensalidade = rec.locator('.cartao-item', { hasText: 'Mensalidade 1' });
  await mensalidade.getByRole('button', { name: 'Receber' }).click();
  await rec.getByLabel('Forma de pagamento').selectOption('DINHEIRO');
  await rec.getByRole('button', { name: /^Receber R\$/ }).click();
  await passo('mensalidade recebida em dinheiro', async () => { await mensalidade.getByText('Paga', { exact: true }).waitFor(); });
  await rec.getByRole('button', { name: 'Criar acesso' }).click();
  const temporariaAluno = await senhaNaTela(rec);
  confere('acesso do aluno criado', temporariaAluno.length >= 8);

  secao('professor: exercício e ficha');
  const ins = await novaPagina();
  await entrar(ins, 'ivo.telas@academia.local', temporariaIns);
  await criarSenha(ins, temporariaIns, 'senha-das-telas-ivo-1');
  await menu(ins, 'Exercícios');
  await ins.getByRole('button', { name: 'Novo exercício' }).click();
  const novoEx = ins.getByRole('form', { name: 'Novo exercício' });
  await novoEx.getByLabel('Nome', { exact: true }).fill('Supino das Telas');
  await novoEx.getByLabel('Grupo muscular', { exact: true }).selectOption('PEITO');
  await novoEx.getByRole('button', { name: 'Salvar' }).click();
  await ins.getByText('Supino das Telas', { exact: true }).waitFor();
  await ins.goto(`${URL}/alunos/${alunoId}`);
  await ins.getByRole('button', { name: 'Montar ficha nova' }).click();
  const editor = ins.getByRole('form', { name: 'Ficha nova' });
  const exercicio = editor.getByLabel('Exercício', { exact: true });
  await exercicio.selectOption(await exercicio.locator('option', { hasText: 'Supino das Telas' }).getAttribute('value'));
  await editor.getByLabel('Carga').fill('20 kg');
  await editor.getByRole('button', { name: 'Salvar ficha' }).click();
  await passo('ficha montada', async () => { await ins.getByRole('heading', { name: 'Treino A' }).waitFor(); });
  await passo('professor não vê CPF nem dinheiro do aluno', async () =>
    (await ins.getByText('CPF', { exact: true }).count()) === 0 && (await ins.getByRole('heading', { name: 'Cobranças' }).count()) === 0);

  secao('aluno no celular');
  const alu = await novaPagina({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  await entrar(alu, 'ana.telas@academia.local', temporariaAluno);
  await criarSenha(alu, temporariaAluno, 'senha-das-telas-ana-1');
  await passo('início: a matrícula do plano', async () => { await alu.getByText('Plano das Telas').waitFor(); });
  await alu.getByRole('link', { name: 'Meu treino' }).first().click();
  await passo('meu treino: o exercício com a carga', async () => {
    await alu.getByText('Supino das Telas').waitFor();
    return alu.getByText(/20 kg/).isVisible();
  });
  await menu(alu, 'Pagamentos');
  await passo('pagamentos: a mensalidade 1 paga, a 2 em aberto', async () => {
    await alu.getByText(/Paga em .*Dinheiro/).waitFor();
    return alu.locator('.cartao-item', { hasText: 'Mensalidade 2' }).isVisible();
  });
  await passo('sem rolagem lateral no celular', async () =>
    alu.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth));

  secao('PWA');
  await passo('manifesto servido e válido', async () => {
    const r = await alu.request.get(`${URL}/manifest.webmanifest`);
    const m = await r.json();
    return (r.ok() && m.display === 'standalone' && m.icons.some((i) => i.sizes === '512x512')) || `${r.status()} ${JSON.stringify(m).slice(0, 120)}`;
  });
  await passo('service worker ativo', async () => {
    await alu.reload();
    // Com tempo-limite: sem service worker, "ready" nunca se resolve.
    return alu.evaluate(() => Promise.race([
      navigator.serviceWorker.ready.then((r) => !!r.active),
      new Promise((resolve) => setTimeout(() => resolve('o service worker não ficou ativo em 10 s'), 10_000)),
    ]));
  });
  await passo('nada da API guardado no aparelho', async () => {
    const guardados = await alu.evaluate(async () => {
      const urls = [];
      for (const nome of await caches.keys()) for (const r of await (await caches.open(nome)).keys()) urls.push(r.url);
      return urls;
    });
    return (guardados.length > 0 && !guardados.some((u) => u.includes('/api/'))) || guardados;
  });
  await passo('sem internet: o app abre (a casca) e pede para entrar', async () => {
    await alu.context().setOffline(true);
    await alu.reload();
    await alu.getByRole('heading', { name: 'Entrar' }).waitFor();
    await alu.context().setOffline(false);
  });

  secao('fechamento e auditoria');
  await rec.goto(`${URL}/caixa`);
  await rec.getByLabel('Dinheiro contado (R$)').waitFor();
  // Contagem às cegas: a recepção conta a gaveta; aqui, o banco diz o que deve haver.
  const esperado = execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'psql', '-U', 'academia', '-d', 'academia', '-At', '-c',
    `SELECT c.troco + coalesce(sum(CASE WHEN l.tipo = 'PAGAMENTO' THEN l.total ELSE -l.total END) FILTER (WHERE l.meio_de_pagamento = 'DINHEIRO'), 0)
     FROM caixas c LEFT JOIN lancamentos l ON l.caixa_id = c.id WHERE c.situacao = 'ABERTO' GROUP BY c.id, c.troco`]).toString().trim();
  await rec.getByLabel('Dinheiro contado (R$)').fill(esperado);
  await rec.getByRole('button', { name: 'Fechar caixa' }).click();
  await passo('caixa fechado sem diferença', async () => {
    await rec.getByText('Fechado', { exact: true }).waitFor();
    return rec.getByText(/diferença R\$\s0,00/).first().isVisible();
  });
  await menu(adm, 'Auditoria');
  await adm.getByLabel('O quê').selectOption('COBRANCA');
  await passo('auditoria: o pagamento, por quem recebeu', async () => {
    await adm.locator('.cartao-item', { hasText: 'Por Rita das Telas' }).first().waitFor();
  });

  const csp = erros.filter((v) => /Content Security Policy|Refused to/i.test(v));
  confere('nenhuma violação da CSP', csp.length === 0, csp.join(' | '));
  const outros = erros.filter((v) => !/status of 4\d\d|Failed to load resource|ERR_INTERNET_DISCONNECTED|Content Security Policy|Refused to/i.test(v));
  confere('nenhum erro de script no console', outros.length === 0, outros.join(' | '));
} catch (e) {
  confere('o roteiro chegou ao fim', false, String(e).split('\n')[0]);
} finally {
  await navegador.close();
  console.log(`\nTELAS: ${ok} ok, ${falhas} falha(s)`);
  process.exitCode = falhas ? 1 : 0;
}
