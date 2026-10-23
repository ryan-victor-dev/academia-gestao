import { useEffect, useState, type ReactNode } from 'react';
import { BrowserRouter, Route, Routes } from 'react-router';
import { Estrutura } from './estrutura/Estrutura';
import { menuDo } from './estrutura/menu';
import { EmBreve } from './paginas/EmBreve';
import { Inicio } from './paginas/Inicio';
import { NaoEncontrada } from './paginas/NaoEncontrada';
import { chamar, renovar, sair, type Usuario } from './sessao/api';
import { ContextoDoUsuario, useUsuario } from './sessao/contexto';
import { TelaDeLogin } from './sessao/TelaDeLogin';
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
      return (
        <ContextoDoUsuario.Provider value={estado.usuario}>
          <AreaLogada aoSair={encerrar} aoTrocarSenha={carregarUsuario} />
        </ContextoDoUsuario.Provider>
      );
  }
}

/** Só as rotas do menu do perfil existem: endereço de outro perfil cai em "não encontrada". */
function AreaLogada({ aoSair, aoTrocarSenha }: { aoSair: () => void; aoTrocarSenha: () => void }) {
  const usuario = useUsuario();
  const prontas: Record<string, ReactNode> = {
    '/senha': <TrocarSenha obrigatoria={false} aoTrocar={aoTrocarSenha} />,
  };
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Estrutura aoSair={aoSair} />}>
          <Route index element={<Inicio />} />
          {menuDo(usuario.perfil).filter((item) => item.caminho !== '/').map((item) => (
            <Route key={item.caminho} path={item.caminho.slice(1)}
                   element={prontas[item.caminho] ?? <EmBreve titulo={item.rotulo} />} />
          ))}
          <Route path="*" element={<NaoEncontrada />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
