# LearningGoalHub Client

Vite + React 18 + TypeScript + Tailwind CSS frontend for the LearningGoalHub server.

## Development

```bash
npm install
npm run dev        # dev server on http://localhost:5173, proxies /api to :8080
```

The dev server proxies `/api` to the Spring backend (`http://localhost:8080` by
default, override with `VITE_API_TARGET`). Start the backend separately:

```bash
docker compose -f ../compose.yaml up -d postgres
SAIA_API_KEY=... ./gradlew :apps:learninggoalhub:server:bootRun   # from repo root
```

## API client

Types are generated from the backend's OpenAPI schema with
[`openapi-typescript`](https://openapi-ts.dev) and consumed through a typed
[`openapi-fetch`](https://openapi-ts.dev/openapi-fetch/) client (`src/api/client.ts`).

To regenerate after a backend API change, with the server running:

```bash
curl -s http://localhost:8080/v3/api-docs -o openapi/openapi.json
npm run generate:api
```

## Scripts

- `npm run dev` – start the dev server
- `npm run build` – typecheck (`tsc -b`) and production build
- `npm run typecheck` – typecheck only
- `npm run lint` – ESLint
- `npm run generate:api` – regenerate `src/api/schema.d.ts` from `openapi/openapi.json`
