<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-08-28 | Updated: 2026-08-28 -->

# k8s/route

## Purpose
Two alternative ways to expose `code-companion-svc` outside the cluster. Apply exactly one: the Gateway API
`HTTPRoute` when a Gateway already exists, or the NGINX `Ingress` with cert-manager TLS otherwise. Neither is
applied by CI.

## Key Files
| File | Description |
|------|-------------|
| `httpRoute.yaml` | `gateway.networking.k8s.io/v1` HTTPRoute `code-companion-http-route`; `PathPrefix /` → `code-companion-svc:80`. Placeholders: `metadata.namespace` (`your-namespace`), `parentRefs[0].name` (`api-gateway-name`), `.namespace` (`gateway-vendor-namespace`), `.sectionName` (`gateway-section-name`), `hostnames[0]` (`your.uri`) |
| `ingress.yaml` | `networking.k8s.io/v1` Ingress `code-companion-ingress` with `kubernetes.io/ingress.class: nginx`, TLS via `cert-manager.io/cluster-issuer`; `Prefix /` → `code-companion-svc:80`. Placeholders: `cert-manager.io/cluster-issuer` (`your-cluster-issuer`), `tls[0].hosts[0]` and `rules[0].host` (`your.uri`), `tls[0].secretName` (`your-secret-tls`) |

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
- Both routes forward everything under `/`. The deploy workflow probes `/api/slack/actuator/health`, so the
  production edge does prefix handling these samples do not express; do not "fix" the samples to add a
  rewrite without checking the live Gateway.
- The backend name and port are hard-coded to `code-companion-svc` / `80`; renaming the Service in
  `../service.yaml` breaks both files silently (the objects apply fine and return 503).
- Same CI caveat as the parent: YAML here counts as source for the lint, test and deploy triggers.

### Testing Requirements
- No automated coverage. `kubectl apply --dry-run=server -f <file>` validates against the installed CRDs;
  for the HTTPRoute, check `kubectl get httproute -n <ns> -o yaml` shows `Accepted=True` and
  `ResolvedRefs=True` after applying.
- End-to-end, the deploy workflow's health probe is the only check that traffic actually reaches the Pod.

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
