# Workshopper Client

React + TypeScript + Vite app for Workshopper. See [../README.md](../README.md) for running the whole app and [../docs/CLIENT.md](../docs/CLIENT.md) for the structure.

```bash
npm install
```

```bash
npm run dev
```

The dev server serves the app at http://localhost:5173/workshopper/ and proxies the API to the Workshopper server (`VITE_API_TARGET`, default `http://localhost:8081`) and the Learning Goal Hub import to `VITE_LGH_TARGET` (default `http://localhost:8080`).

Unit tests (Vitest; the pure logic in `src/lib` and `src/hooks`, e.g. what a block shows, step and duration edits, slide re-matching, download names):

```bash
npm test
```

Type-check:

```bash
npx tsc --noEmit
```

Production build (type-check + bundle into `dist/`):

```bash
npm run build
```
