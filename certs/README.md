# Corporate CA certificates (vendored, pinned)

Drop the corporate **root CA** certificate (PEM, `*.crt`/`*.pem`) here so the image trusts
corp HTTPS endpoints (infosrv upstream, corp git). The `Dockerfile` COPYs this dir and imports
every cert into the JRE truststore via keytool. This is a **public** CA cert — safe to commit;
never put private keys here.
