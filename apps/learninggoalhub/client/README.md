# LearningGoalHub Client

Vite + React 18 + TypeScript + Tailwind CSS client for the LearningGoalHub server.

## Development

```bash
npm install
npm run dev        # http://localhost:5173/learninggoalhub/
```

The app lives under the `/learninggoalhub` path prefix, in development as in production. The dev
server proxies `/learninggoalhub/api` to the Spring server (`http://localhost:8080` by default,
override with `VITE_API_TARGET`) and strips the prefix, as the production proxy does. Start the
database and server first; the [app README](../README.md#run-locally) has the commands, including
the throwaway SAML key pair the server needs to boot.

## API client

Types are generated from the backend's OpenAPI schema with
[`openapi-typescript`](https://openapi-ts.dev) and consumed through a typed
[`openapi-fetch`](https://openapi-ts.dev/openapi-fetch/) client (`src/api/client.ts`).

To regenerate after a server API change, with the server running:

```bash
curl -s http://localhost:8080/v3/api-docs -o openapi/openapi.json
npm run generate:api
```

## Scripts

- `npm run dev` – start the dev server
- `npm run build` – typecheck (`tsc -b`) and production build
- `npm run typecheck` – typecheck only
- `npm run lint` – ESLint
- `npm test` – Vitest
- `npm run generate:api` – regenerate `src/api/schema.d.ts` from `openapi/openapi.json`
