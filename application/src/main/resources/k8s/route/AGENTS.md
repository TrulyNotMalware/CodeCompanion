<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-10-08 -->

# k8s/route

## Purpose
Two alternative ways to expose `code-companion-svc` outside the cluster. Apply exactly one: the Gateway API
`HTTPRoute` when a Gateway already exists, or the NGINX `Ingress` with cert-manager TLS otherwise. Neither is
applied by CI.

## Key Files
| File | Description |
|------|-------------|
| `httpRoute.yaml` | `gateway.networking.k8s.io/v1` HTTPRoute `code-companion-http-route`; one rule matching `PathPrefix /api/slack`, `PathPrefix /api/slash` or `Exact /oauth/google/callback` → `code-companion-svc:80`. Placeholders: `metadata.namespace` (`your-namespace`), `parentRefs[0].name` (`api-gateway-name`), `.namespace` (`gateway-vendor-namespace`), `.sectionName` (`gateway-section-name`), `hostnames[0]` (`your.uri`) |
| `ingress.yaml` | `networking.k8s.io/v1` Ingress `code-companion-ingress` with `kubernetes.io/ingress.class: nginx`, TLS via `cert-manager.io/cluster-issuer`; two `Prefix` paths, `/api/slack` and `/api/slash`, and the `Exact` path `/oauth/google/callback` → `code-companion-svc:80`. Placeholders: `cert-manager.io/cluster-issuer` (`your-cluster-issuer`), `tls[0].hosts[0]` and `rules[0].host` (`your.uri`), `tls[0].secretName` (`your-secret-tls`) |

## For AI Agents

### Working In This Directory
- `httpRoute.yaml` is the only manifest in `k8s/` that carries a `metadata.namespace`; it must be the
  namespace the Service lives in (`api-service` in the deploy workflow), while `parentRefs.namespace` is the
  Gateway's namespace. A cross-namespace attachment only works if the Gateway listener's
  `allowedRoutes.namespaces` admits the route's namespace, and `sectionName` must equal a listener name on
  that Gateway.
- `ingress.yaml` uses the deprecated `kubernetes.io/ingress.class` annotation rather than
  `spec.ingressClassName`; NGINX still honours it, but a controller configured to ignore the annotation
  will not pick the object up.
- The TLS host and the rule host are the same placeholder value and have to be replaced together; cert-manager
  writes the certificate into `secretName`, so that Secret must not pre-exist with other content.
- Both routes forward only `/api/slack` and `/api/slash` (the paths `SlackEventController` and
  `SlashCommandController` serve) and the exact path `/oauth/google/callback` (`GoogleOAuthCallbackController`, the
  browser redirect from Google's consent screen; it is registered only while the calendar integration is enabled,
  so it answers `404` otherwise), without any rewrite. Both prefix match types are element-wise, so `/api/slackx` does
  not match. **Do not widen them to `/api` or `/`:** the app serves the unauthenticated actuator at `/actuator`
  (prod) or `/api/actuator` (dev, local and slack-live, where local also exposes `loggers` and `threaddump`), and
  `/mcp`, on the same port, and none of them may be reachable from outside. A new public controller path needs a
  new entry in both files. The production host is not served by these samples:
  it is fronted by a bearer-authenticating layer outside this repository (on 2026-09-28 every probed path, nonexistent
  ones included, answered `401` with `WWW-Authenticate: Bearer`, except `GET /actuator/health`, which answered a
  `404` JSON body that is not this application's error format), so which paths it forwards to the app is unknown
  from outside and has to be confirmed by whoever operates it.
- The backend name and port are hard-coded to `code-companion-svc` / `80`; renaming the Service in
  `../service.yaml` breaks both files silently (the objects apply fine and return 503).
- Same CI caveat as the parent: YAML here counts as source for the lint, test and deploy triggers.

### Testing Requirements
- No automated coverage. `kubectl apply --dry-run=server -f <file>` validates against the installed CRDs;
  for the HTTPRoute, check `kubectl get httproute -n <ns> -o yaml` shows `Accepted=True` and
  `ResolvedRefs=True` after applying.
- The deploy workflow's health check goes through the API server's service proxy, not through these routes,
  so it does not prove external reachability. A signed Slack request that shows up in the app's logs does; a `401`
  from outside does not, because the edge in front of the production host answered `401` for every probed path
  but one (`GET /actuator/health`, a `404` that did not come from this app either).

### Common Patterns
- Placeholders are lower-case `your-*` / `your.uri`; keep that convention so README's "Configure:" lists
  remain accurate.

## Dependencies

### Internal
- `../service.yaml` — backend `code-companion-svc:80` both files reference
- `../README.md` — which route to pick and the prerequisites for each

### External
Gateway API CRDs plus a provisioned Gateway (HTTPRoute), or NGINX Ingress Controller plus cert-manager with a
ClusterIssuer (Ingress).

<!-- MANUAL: Any manually added notes below this line are preserved on regeneration -->
