import { PessoasDaFolha } from './PessoasDaFolha';

/** Pagamentos à equipe (ADR 0049, só o ADMIN): só o controle do que foi combinado e pago, sem calcular imposto. */
export function Folha() {
  return (
    <>
      <h1>Folha</h1>
      <PessoasDaFolha />
    </>
  );
}
