import { Link } from 'react-router';
import { menuDo } from '../estrutura/menu';
import { useUsuario } from '../sessao/contexto';

/** Início: atalhos para o que o perfil pode fazer. */
export function Inicio() {
  const usuario = useUsuario();
  const atalhos = menuDo(usuario.perfil).filter((item) => item.caminho !== '/');
  return (
    <>
      <h1>Olá, {usuario.nome}</h1>
      <ul className="atalhos">
        {atalhos.map((item) => (
          <li key={item.caminho}>
            <Link to={item.caminho}>{item.rotulo}</Link>
          </li>
        ))}
      </ul>
    </>
  );
}
