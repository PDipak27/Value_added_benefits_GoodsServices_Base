# TLS certificates (§E2 — edge TLS)

The **gateway** (`:8089`) and **Keycloak** (`:8088`) serve HTTPS with a locally-trusted
**mkcert** certificate; everything else stays HTTP on the trusted local network
(edge-only TLS, DD-31 topology). The cert files live here, are **generated on your
machine**, and are **gitignored** (they hold a private key).

## One-time setup

1. **Install mkcert and its local CA — including the Java truststore** so the host-run
   Java services (gateway, order, ott, fulfilment, and the e2e JVM) trust the cert.
   `mkcert -install` writes the CA into the JDK's `cacerts` **only if `JAVA_HOME` is set**:
   ```powershell
   choco install mkcert            # or: scoop install mkcert / brew install mkcert
   $env:JAVA_HOME = "C:\path\to\jdk"   # the JDK the services run on
   mkcert -install
   ```
2. **Generate the cert/key** for localhost (run from this `deploy/tls/` directory):
   ```powershell
   mkcert -cert-file keycloak.crt.pem -key-file keycloak.key.pem localhost 127.0.0.1 ::1
   ```
3. **Build the gateway's PKCS12 keystore** from that same cert/key:
   ```powershell
   openssl pkcs12 -export -inkey keycloak.key.pem -in keycloak.crt.pem `
     -name gateway -out gateway-keystore.p12 -passout pass:changeit
   ```

Result — three gitignored files in `deploy/tls/`:
| File | Used by |
|---|---|
| `keycloak.crt.pem`, `keycloak.key.pem` | mounted into the Keycloak container (`KC_HTTPS_CERTIFICATE_*`) |
| `gateway-keystore.p12` | the gateway (`server.ssl`, alias `gateway`, password `changeit`) |

## Notes
- Keycloak publishes HTTPS on host **:8088** (container `:8443`); the issuer is
  `https://localhost:8088/realms/vab`, and the realm's `sslRequired` is `external`.
- The gateway serves HTTPS on **:8089**; run it from `source/vabags_base` so the default
  keystore path `./deploy/tls/gateway-keystore.p12` resolves, or set
  `GATEWAY_SSL_KEYSTORE` to an absolute `file:` URL.
- **Run without TLS:** `GATEWAY_SSL_ENABLED=false`, set `KEYCLOAK_ISSUER` /
  `KEYCLOAK_TOKEN_URI` back to `http://localhost:8088/...`, and revert the compose
  Keycloak block (`8088:8080`, drop the cert mount).
- `mkcert -install` (with `JAVA_HOME` set) means the Java services trust the cert with
  no per-service truststore edits; the e2e suite also calls
  `RestAssured.useRelaxedHTTPSValidation()` as a safety net.
