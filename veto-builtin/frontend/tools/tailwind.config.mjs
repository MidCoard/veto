/** @type {import('tailwindcss').Config} */
export default {
  content: ['./*.tsx'],
  important: '.veto-tools',
  corePlugins: { preflight: false },
  theme: {
    extend: {
      colors: {
        // "Graphite console" token set — values come from CSS variables (see
        // index.css) so the same classes drive both the dark and light themes.
        // Channels are RGB triplets to keep /opacity modifiers working.
        ink: 'rgb(var(--ink) / <alpha-value>)',       // app background
        panel: 'rgb(var(--panel) / <alpha-value>)',   // side panels, cards
        raised: 'rgb(var(--raised) / <alpha-value>)', // inputs, hover states, popovers
        rule: 'rgb(var(--rule) / <alpha-value>)',     // borders / hairlines
        paper: 'rgb(var(--paper) / <alpha-value>)',   // primary text
        dim: 'rgb(var(--dim) / <alpha-value>)',       // secondary text, labels, timestamps
        accent: 'rgb(var(--accent) / <alpha-value>)', // cool cyan — agent activity + primary actions ONLY
        onaccent: 'rgb(var(--onaccent) / <alpha-value>)', // text on accent-filled buttons
        verdict: 'rgb(var(--verdict) / <alpha-value>)', // red — blocked / veto semantics ONLY
        pass: 'rgb(var(--pass) / <alpha-value>)',     // green — pass semantics ONLY
        tone: {
          slate: 'rgb(var(--tone-slate) / <alpha-value>)',
          violet: 'rgb(var(--tone-violet) / <alpha-value>)',
          sky: 'rgb(var(--tone-sky) / <alpha-value>)',
          pink: 'rgb(var(--tone-pink) / <alpha-value>)',
          indigo: 'rgb(var(--tone-indigo) / <alpha-value>)',
          fuchsia: 'rgb(var(--tone-fuchsia) / <alpha-value>)',
          amber: 'rgb(var(--tone-amber) / <alpha-value>)',
          teal: 'rgb(var(--tone-teal) / <alpha-value>)',
          blue: 'rgb(var(--tone-blue) / <alpha-value>)',
          emerald: 'rgb(var(--tone-emerald) / <alpha-value>)',
          purple: 'rgb(var(--tone-purple) / <alpha-value>)',
        },
        // Fixed dark surface for code blocks in BOTH themes.
        codebg: '#14181F',
      },
      fontFamily: {
        display: ['"Space Mono"', 'monospace'],
        sans: ['"IBM Plex Sans"', 'system-ui', 'sans-serif'],
        mono: ['"IBM Plex Mono"', 'ui-monospace', 'monospace'],
      },
    },
  },
  plugins: [],
}
