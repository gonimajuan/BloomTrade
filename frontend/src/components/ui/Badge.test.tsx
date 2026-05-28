import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Badge } from './Badge';

describe('<Badge />', () => {
  it('renders with neutral variant by default', () => {
    render(<Badge>Pendiente</Badge>);
    const badge = screen.getByText('Pendiente');
    expect(badge).toBeInTheDocument();
    expect(badge.className).toMatch(/bg-slate-800/);
  });

  it('applies success variant emerald palette', () => {
    const { container } = render(<Badge variant="success">OK</Badge>);
    expect((container.firstChild as HTMLElement).className).toMatch(/emerald/);
  });

  it('applies error variant rose palette', () => {
    const { container } = render(<Badge variant="error">FAIL</Badge>);
    expect((container.firstChild as HTMLElement).className).toMatch(/rose/);
  });

  it('applies accent variant violet palette', () => {
    const { container } = render(<Badge variant="accent">NEW</Badge>);
    expect((container.firstChild as HTMLElement).className).toMatch(/violet/);
  });
});
