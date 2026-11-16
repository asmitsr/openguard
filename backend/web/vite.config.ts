import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { viteSingleFile } from 'vite-plugin-singlefile'

// The Worker serves the dashboard as ONE inlined HTML string (esbuild
// --loader:.html=text), so the build must emit a single self-contained
// dist/index.html. vite-plugin-singlefile inlines all local JS + CSS;
// the MapLibre CDN tags in index.html stay external, as before.
export default defineConfig({
  plugins: [react(), viteSingleFile()],
})
