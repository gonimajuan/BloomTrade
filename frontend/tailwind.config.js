/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: [
          '"Space Grotesk Variable"',
          'ui-sans-serif',
          'system-ui',
          '-apple-system',
          'Segoe UI',
          'sans-serif',
        ],
      },
      boxShadow: {
        'glass-sm':
          '0 1px 2px 0 rgb(0 0 0 / 0.4), inset 0 1px 0 0 rgb(255 255 255 / 0.04)',
        glass:
          '0 8px 32px -4px rgb(0 0 0 / 0.5), inset 0 1px 0 0 rgb(255 255 255 / 0.06)',
        'glass-lg':
          '0 24px 64px -12px rgb(0 0 0 / 0.6), inset 0 1px 0 0 rgb(255 255 255 / 0.08)',
        'glow-violet':
          '0 0 24px -4px rgb(139 92 246 / 0.5), 0 0 48px -12px rgb(139 92 246 / 0.3)',
        'glow-violet-sm': '0 0 12px -2px rgb(139 92 246 / 0.4)',
        'glow-emerald-sm': '0 0 12px -2px rgb(52 211 153 / 0.4)',
        'glow-rose-sm': '0 0 12px -2px rgb(251 113 133 / 0.4)',
      },
      keyframes: {
        'orb-drift-1': {
          '0%, 100%': { transform: 'translate(0, 0) scale(1)' },
          '33%': { transform: 'translate(40px, -30px) scale(1.05)' },
          '66%': { transform: 'translate(-30px, 20px) scale(0.95)' },
        },
        'orb-drift-2': {
          '0%, 100%': { transform: 'translate(0, 0) scale(1)' },
          '50%': { transform: 'translate(-50px, 40px) scale(1.1)' },
        },
        'orb-drift-3': {
          '0%, 100%': { transform: 'translate(0, 0) scale(1)' },
          '50%': { transform: 'translate(30px, -50px) scale(0.9)' },
        },
        'fade-in': {
          from: { opacity: '0' },
          to: { opacity: '1' },
        },
        'slide-up': {
          from: { opacity: '0', transform: 'translateY(8px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
      },
      animation: {
        'orb-drift-1': 'orb-drift-1 24s ease-in-out infinite',
        'orb-drift-2': 'orb-drift-2 32s ease-in-out infinite',
        'orb-drift-3': 'orb-drift-3 28s ease-in-out infinite',
        'fade-in': 'fade-in 400ms ease-out',
        'slide-up': 'slide-up 400ms ease-out',
      },
    },
  },
  plugins: [],
};
