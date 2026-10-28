import { useEffect, useState, type ReactNode } from 'react';
import { BrowserRouter, Route, Routes } from 'react-router';
import { Estrutura } from './estrutura/Estrutura';
import { menuDo } from './estrutura/menu';
import { EmBreve } from './paginas/EmBreve';
import { CadastroDoAluno } from './paginas/alunos/CadastroDoAluno';
import { ListaDeAlunos } from './paginas/alunos/ListaDeAlunos';
import { NovoAluno } from './paginas/alunos/NovoAluno';
import { CaixaDoDia } from './paginas/caixa/CaixaDoDia';
import { CobrancasAtrasadas } from './paginas/cobrancas/CobrancasAtrasadas';
import { Auditoria } from './paginas/admin/Auditoria';
import { ConfiguracaoDaCobranca } from './paginas/admin/ConfiguracaoDaCobranca';
import { PagamentosOnline } from './paginas/admin/PagamentosOnline';
import { Equipe } from './paginas/admin/Equipe';
import { InicioDoAluno } from './paginas/aluno/InicioDoAluno';
import { MeusPagamentos } from './paginas/aluno/MeusPagamentos';
import { CartaoSimulado } from './paginas/aluno/CartaoSimulado';
import { MeuTreino } from './paginas/aluno/MeuTreino';
import { Inicio } from './paginas/Inicio';
import { ListaDePlanos } from './paginas/planos/ListaDePlanos';
import { CatalogoDeExercicios } from './paginas/treinos/CatalogoDeExercicios';
import { RevisoesVencidas } from './paginas/treinos/RevisoesVencidas';
import { NaoEncontrada } from './paginas/NaoEncontrada';
import { chamar, renovar, sair, type Perfil, type Usuario } from './sessao/api';
import { ContextoDoUsuario, useUsuario } from './sessao/contexto';
import { TelaDeLogin } from './sessao/TelaDeLogin';
import { ConfirmarEmail } from './sessao/ConfirmarEmail';
import { TrocarSenha } from './sessao/TrocarSenha';

type Estado = { tipo: 'carregando' } | { tipo: 'fora' } | { tipo: 'dentro'; usuario: Usuario };

/** Ao abrir a página, tenta retomar a sessão pelo cookie; sem ela, mostra o login. */
export function App() {
  const [estado, setEstado] = useState<Estado>({ tipo: 'carregando' });

  async function carregarUsuario() {
    try {
      setEstado({ tipo: 'dentro', usuario: await chamar<Usuario>('/api/me') });
    } catch {
      setEstado({ tipo: 'fora' });
    }
  }

  useEffect(() => {
    let ativo = true;
    renovar().then((retomou) => {
      if (!ativo) return;
      if (retomou) {
        void carregarUsuario();
      } else {
        setEstado({ tipo: 'fora' });
      }
    });
    return () => {
      ativo = false;
    };
  }, []);

  async function encerrar() {
    await sair();
    setEstado({ tipo: 'fora' });
  }

  switch (estado.tipo) {
    case 'carregando':
      return <p className="carregando" role="status">Carregando…</p>;
    case 'fora':
      return <TelaDeLogin aoEntrar={carregarUsuario} />;
    case 'dentro':
      if (estado.usuario.precisaTrocarSenha) {
        // Senha temporária: a API só libera a troca; nenhuma outra tela funcionaria.
        return (
          <main className="pagina login">
            <TrocarSenha obrigatoria aoTrocar={carregarUsuario} />
            <button type="button" className="secundario" onClick={encerrar}>Sair</button>
          </main>
        );
      }
      if (estado.usuario.precisaConfirmarEmail) {
        // E-mail não confirmado (ADR 0038): a API só libera a confirmação.
        return (
          <main className="pagina login">
            <ConfirmarEmail email={estado.usuario.email} aoConfirmar={carregarUsuario}
                            somenteLeitura={estado.usuario.somenteLeitura} />
            <button type="button" className="secundario" onClick={encerrar}>Sair</button>
          </main>
        );
      }
      return (
        <ContextoDoUsuario.Provider value={estado.usuario}>
          <AreaLogada aoSair={encerrar} aoTrocarSenha={carregarUsuario} />
        </ContextoDoUsuario.Provider>
      );
  }
}

/** Telas além das do menu, cada uma só para os perfis que a API deixa usar. */
const SUBROTAS: Array<{ caminho: string; perfis: Perfil[]; elemento: ReactNode }> = [
  { caminho: '/alunos/novo', perfis: ['ADMIN', 'RECEPCAO'], elemento: <NovoAluno /> },
  { caminho: '/alunos/:id', perfis: ['ADMIN', 'RECEPCAO', 'INSTRUTOR'], elemento: <CadastroDoAluno /> },
  // A "página da empresa" do cartão no modo de teste (ADR 0037); a API recusa se não for o simulado.
  { caminho: '/cartao-simulado/:id', perfis: ['ALUNO'], elemento: <CartaoSimulado /> },
];

/** Só as rotas do perfil existem: endereço de outro perfil cai em "não encontrada". */
function AreaLogada({ aoSair, aoTrocarSenha }: { aoSair: () => void; aoTrocarSenha: () => void }) {
  const usuario = useUsuario();
  const prontas: Record<string, ReactNode> = {
    '/alunos': <ListaDeAlunos />,
    '/cobrancas': <CobrancasAtrasadas />,
    '/caixa': <CaixaDoDia />,
    '/exercicios': <CatalogoDeExercicios />,
    '/meu-treino': <MeuTreino />,
    '/minhas-cobrancas': <MeusPagamentos />,
    '/planos': <ListaDePlanos />,
    '/equipe': <Equipe />,
    '/configuracao': <ConfiguracaoDaCobranca />,
    '/pagamento-online': <PagamentosOnline />,
    '/auditoria': <Auditoria />,
    '/revisoes': <RevisoesVencidas />,
    '/senha': <TrocarSenha obrigatoria={false} aoTrocar={aoTrocarSenha} />,
  };
  const rotas = [
    ...menuDo(usuario.perfil).filter((item) => item.caminho !== '/')
      .map((item) => ({ caminho: item.caminho, elemento: prontas[item.caminho] ?? <EmBreve titulo={item.rotulo} /> })),
    ...SUBROTAS.filter((r) => r.perfis.includes(usuario.perfil)),
  ];
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Estrutura aoSair={aoSair} />}>
          <Route index element={usuario.perfil === 'ALUNO' ? <InicioDoAluno /> : <Inicio />} />
          {rotas.map((r) => <Route key={r.caminho} path={r.caminho.slice(1)} element={r.elemento} />)}
          <Route path="*" element={<NaoEncontrada />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
