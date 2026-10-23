export interface Pagina<T> {
  conteudo: T[];
  pagina: number;
  tamanho: number;
  totalElementos: number;
  totalPaginas: number;
}

export function Paginacao({ pagina, aoMudar }: { pagina: Pagina<unknown>; aoMudar: (n: number) => void }) {
  if (pagina.totalPaginas <= 1) return null;
  return (
    <nav className="paginacao" aria-label="Páginas">
      <button type="button" className="secundario" disabled={pagina.pagina === 0} onClick={() => aoMudar(pagina.pagina - 1)}>
        Anterior
      </button>
      <span>Página {pagina.pagina + 1} de {pagina.totalPaginas}</span>
      <button type="button" className="secundario" disabled={pagina.pagina + 1 >= pagina.totalPaginas}
              onClick={() => aoMudar(pagina.pagina + 1)}>
        Próxima
      </button>
    </nav>
  );
}
