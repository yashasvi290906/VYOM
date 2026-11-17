# VYOM Gateway & Frontend Documentation

## 1. Node.js API Gateway

The Gateway is a lightweight Express application situated in `gateway/`, listening on port 3000. It acts as the single entrypoint for both static UI assets and REST API proxying.

### Configuration
Managed via environment variables with safe defaults:
- `PORT`: `3000`
- `BACKEND_BASE_URL`: `http://localhost:8080`
- `BACKEND_TIMEOUT_MS`: `5000`
- `LOG_LEVEL`: `info`

### Responsibilities
1. **Request Tracing:**
   Generates a correlation ID if absent (`req-<uuid8>`) and attaches it to response headers (`X-Request-Id` and `X-Correlation-Id`).
2. **Reverse Proxying:**
   Uses native Node.js `fetch` with `AbortSignal.timeout(BACKEND_TIMEOUT_MS)`.
3. **Fault Containment:**
   - Unreachable upstream (`ECONNREFUSED`): Returns `503 Service Unavailable` with `BACKEND_UNAVAILABLE`.
   - Timeout (>5000ms): Returns `504 Gateway Timeout` with `GATEWAY_TIMEOUT`.
   - Forwarding: For 400, 404, 409, 422, 500, preserves HTTP status and error body unchanged.
4. **Health Check:**
   `GET /health` probes the upstream Spring Boot application and returns the live status of both Gateway and Backend.

---

## 2. Browser Frontend Design

The frontend is a single-page dashboard residing in `gateway/public/`, built entirely with native web standards:
- **HTML5:** Semantic elements (`<main>`, `<section>`, `<header>`, `<form>`, `<label>`, `<input>`, `<button>`, `<table>`).
- **CSS3:** Custom properties (CSS variables), CSS Grid, Flexbox, accessible color contrast (WCAG AA compliant).
- **JavaScript (ES2022+):** Pure vanilla JavaScript partitioned into three modules:
  - `js/api.js`: Thin HTTP abstraction and UUIDv4 generation.
  - `js/ui.js`: View manipulation, toast alerts, loading spinners, table rendering.
  - `js/app.js`: Event orchestration and state coordination.

### Safe DOM Manipulation & XSS Defense
To eliminate Cross-Site Scripting (XSS) risks:
- User-supplied strings (owner names, descriptions, wallet IDs) and backend responses are **never** injected via `innerHTML`.
- All DOM nodes are generated via `document.createElement()` and populated exclusively via `textContent`.
- Table rendering loops construct `<tr>` and `<td>` elements programmatically.

### Responsive Design
- **Desktop (>=900px):** 2-column dashboard card grid with a full-width bottom card for Transaction History.
- **Mobile (<600px):** Gracefully stacks into a single vertical column with no horizontal overflow, fully responsive down to 360px viewport width.
- Inputs and buttons expand to full tap width on mobile screens for touch friendliness.

### Live Health Monitoring
The UI continuously checks `GET /health` every 8 seconds:
- **Green Dot:** `Backend Connected` (Spring Boot and PostgreSQL operational).
- **Red Dot:** `Backend Offline` (Gateway cannot reach Spring Boot).
- **Amber Dot:** `Degraded` (Non-standard response).
