import { createContext, useContext } from 'react';
import type { Usuario } from './api';

/** Quem está usando o sistema; as telas leem daqui o nome e o perfil. */
export const ContextoDoUsuario = createContext<Usuario | null>(null);

export function useUsuario(): Usuario {
  const usuario = useContext(ContextoDoUsuario);
  if (usuario === null) throw new Error('useUsuario fora da área logada');
  return usuario;
}
