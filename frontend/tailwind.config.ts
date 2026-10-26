import type { Config } from 'tailwindcss';

const config: Config = {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        background: 'hsl(var(--color-bg))',
        surface: 'hsl(var(--color-surface))',
        'surface-muted': 'hsl(var(--color-surface-muted))',
        border: 'hsl(var(--color-border))',
        text: 'hsl(var(--color-text))',
        muted: 'hsl(var(--color-muted))',
        primary: 'hsl(var(--color-primary))',
        success: 'hsl(var(--color-success))',
        warning: 'hsl(var(--color-warning))',
        danger: 'hsl(var(--color-danger))',
        info: 'hsl(var(--color-info))',
      },
      borderRadius: {
        sm: '0.375rem',
        md: '0.5rem',
        lg: '0.75rem',
      },
      boxShadow: {
        panel: '0 1px 2px rgba(16, 24, 40, 0.06), 0 1px 3px rgba(16, 24, 40, 0.1)',
      },
      fontFamily: {
        sans: ['"Public Sans"', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
};

export default config;
