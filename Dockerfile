FROM harbor.online.tkbbank.ru/custom-base-images/openjre-alpine-musl:21.0.8
RUN addgroup -S app && adduser -S -G app -h /app app
RUN apk add --no-cache curl

# Trust the corporate CA (vendored in certs/, pinned, code-reviewed) so HTTPS calls validate
# (e.g. the infosrv upstream / corp git). Drop the corp ROOT CA (PEM) into certs/.
COPY certs/ /usr/local/share/corp-ca/
RUN set -eu; \
    : "${JAVA_HOME:?JAVA_HOME must be set}"; \
    for c in /usr/local/share/corp-ca/*.crt /usr/local/share/corp-ca/*.pem; do \
        [ -f "$c" ] || continue; \
        keytool -importcert -trustcacerts -noprompt -alias "corp-$(basename "$c")" \
            -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit -file "$c"; \
    done

WORKDIR /app
COPY deploy/sbp-router-*.jar /app/app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -Duser.timezone=GMT+3 -jar /app/app.jar"]
