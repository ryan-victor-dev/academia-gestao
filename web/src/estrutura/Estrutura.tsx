import { NavLink, Outlet } from 'react-router';
import { useUsuario } from '../sessao/contexto';
import { menuDo, NOME_DO_PERFIL } from './menu';

/** Moldura da área logada: topo com quem está usando, menu do perfil e a tela da vez. */
export function Estrutura({ aoSair }: { aoSair: () => void }) {
  const usuario = useUsuario();
  return (
    <div className="estrutura">
      <header className="barra">
        <div className="marca">Academia</div>
        <div className="quem">
          <span className="nome">{usuario.nome}</span>
          <span className="perfil">{NOME_DO_PERFIL[usuario.perfil]}</span>
        </div>
        <button type="button" className="secundario" onClick={aoSair}>
          Sair
        </button>
      </header>
      <nav className="menu" aria-label="Menu">
        {menuDo(usuario.perfil).map((item) => (
          <NavLink key={item.caminho} to={item.caminho} end={item.caminho === '/'}>
            {item.rotulo}
          </NavLink>
        ))}
      </nav>
      {usuario.somenteLeitura && (
        <p className="aviso faixa" role="status">
          Seu cadastro está inativo: você pode consultar seus dados, mas não alterar nada.
          Fale com a recepção para reativar.
        </p>
      )}
      <main className="pagina">
        <Outlet />
      </main>
    </div>
  );
}
