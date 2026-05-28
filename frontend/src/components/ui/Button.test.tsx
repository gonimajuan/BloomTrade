import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Button } from './Button';

describe('<Button />', () => {
  it('renders children with primary variant and md size by default', () => {
    render(<Button>Comprar</Button>);
    const btn = screen.getByRole('button', { name: 'Comprar' });
    expect(btn).toBeInTheDocument();
    expect(btn.className).toMatch(/bg-violet-600/);
    expect(btn.className).toMatch(/h-10/);
  });

  it('applies destructive variant', () => {
    render(<Button variant="destructive">Cancelar</Button>);
    expect(screen.getByRole('button').className).toMatch(/bg-rose-600/);
  });

  it('applies ghost variant', () => {
    render(<Button variant="ghost">Mantener</Button>);
    expect(screen.getByRole('button').className).toMatch(/bg-transparent/);
  });

  it('applies size sm', () => {
    render(<Button size="sm">x</Button>);
    expect(screen.getByRole('button').className).toMatch(/h-8/);
  });

  it('disables button and shows spinner when isLoading', () => {
    render(<Button isLoading>Guardando</Button>);
    const btn = screen.getByRole('button');
    expect(btn).toBeDisabled();
    // Spinner is rendered as svg via Loader2
    expect(btn.querySelector('svg')).toBeInTheDocument();
  });

  it('invokes onClick when not disabled', async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Click</Button>);
    await userEvent.click(screen.getByRole('button', { name: 'Click' }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('does not invoke onClick when disabled', async () => {
    const onClick = vi.fn();
    render(
      <Button disabled onClick={onClick}>
        Click
      </Button>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Click' }));
    expect(onClick).not.toHaveBeenCalled();
  });
});
