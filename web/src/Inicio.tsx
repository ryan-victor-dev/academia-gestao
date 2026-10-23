import type { Perfil, Usuario } from './sessao/api';

const NOME_DO_PERFIL: Record<Perfil, string> = {
  ADMIN: 'Administração',
  RECEPCAO: 'Recepção',
  INSTRUTOR: 'Professor',
  ALUNO: 'Aluno',
};

/** Tela inicial provisória: as telas de cada perfil vêm nas próximas partes. */
export function Inicio({ usuario, aoSair }: { usuario: Usuario; aoSair: () => void }) {
  return (
    <main className="pagina">
      <header className="topo">
        <div>
          <h1>Olá, {usuario.nome}</h1>
          <p className="perfil">{NOME_DO_PERFIL[usuario.perfil]}</p>
        </div>
        <button type="button" className="secundario" onClick={aoSair}>
          Sair
        </button>
      </header>
      {usuario.precisaTrocarSenha && (
        <p className="aviso" role="alert">
          Sua senha é temporária. A troca de senha nesta tela chega na próxima versão.
        </p>
      )}
    </main>
  );
}
