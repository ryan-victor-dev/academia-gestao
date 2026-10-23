import { Link } from 'react-router';

export function NaoEncontrada() {
  return (
    <>
      <h1>Página não encontrada</h1>
      <p className="suave">
        O endereço não existe ou não está no seu menu. <Link to="/">Voltar ao início</Link>
      </p>
    </>
  );
}
