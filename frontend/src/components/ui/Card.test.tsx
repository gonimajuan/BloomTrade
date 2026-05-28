import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { Card } from './Card';

describe('<Card />', () => {
  it('applies glass variant by default with backdrop blur', () => {
    const { container } = render(<Card>contenido</Card>);
    const card = container.firstChild as HTMLElement;
    expect(card.className).toMatch(/bg-slate-900\/40/);
    expect(card.className).toMatch(/backdrop-blur-xl/);
    expect(card.className).toMatch(/rounded-2xl/);
  });

  it('applies glass-elevated variant with shadow-glass-lg', () => {
    const { container } = render(<Card variant="glass-elevated">x</Card>);
    expect((container.firstChild as HTMLElement).className).toMatch(/shadow-glass-lg/);
    expect((container.firstChild as HTMLElement).className).toMatch(/bg-slate-900\/60/);
  });

  it('applies glass-outline variant without solid bg', () => {
    const { container } = render(<Card variant="glass-outline">x</Card>);
    expect((container.firstChild as HTMLElement).className).toMatch(/bg-transparent/);
  });

  it('merges custom className via cn()', () => {
    const { container } = render(<Card className="p-8">x</Card>);
    expect((container.firstChild as HTMLElement).className).toMatch(/p-8/);
  });
});
