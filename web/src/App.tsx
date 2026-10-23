import { useEffect, useState } from 'react';
import { chamar, renovar, sair, type Usuario } from './sessao/api';
import { TelaDeLogin } from './sessao/TelaDeLogin';
import { Inicio } from './Inicio';

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
      return <Inicio usuario={estado.usuario} aoSair={encerrar} />;
  }
}
