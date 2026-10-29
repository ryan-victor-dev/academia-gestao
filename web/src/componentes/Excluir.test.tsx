import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Excluir } from './Excluir';

describe('excluir', () => {
  it('só exclui depois de confirmar; voltar não exclui', async () => {
    const aoExcluir = vi.fn(async () => {});
    render(<Excluir nome="Mensal" aoExcluir={aoExcluir} />);

    await userEvent.click(screen.getByRole('button', { name: 'Excluir' }));
    expect(screen.getByText('Excluir Mensal de vez?')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Voltar' }));
    expect(aoExcluir).not.toHaveBeenCalled();
    expect(screen.queryByText('Excluir Mensal de vez?')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Excluir' }));
    await userEvent.click(screen.getByRole('button', { name: 'Sim, excluir' }));
    expect(aoExcluir).toHaveBeenCalledTimes(1);
    // Recusado (o cadastro tem histórico), volta ao botão: quem chama mostra o motivo.
    expect(screen.getByRole('button', { name: 'Excluir' })).toBeInTheDocument();
  });

  it('enquanto exclui, não deixa clicar de novo', async () => {
    let terminar = () => {};
    const aoExcluir = vi.fn(() => new Promise<void>((r) => { terminar = r; }));
    render(<Excluir nome="Mensal" aoExcluir={aoExcluir} />);

    await userEvent.click(screen.getByRole('button', { name: 'Excluir' }));
    await userEvent.click(screen.getByRole('button', { name: 'Sim, excluir' }));
    expect(screen.getByRole('button', { name: 'Excluindo…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Voltar' })).toBeDisabled();
    terminar();
    expect(await screen.findByRole('button', { name: 'Excluir' })).toBeInTheDocument();
  });
});
