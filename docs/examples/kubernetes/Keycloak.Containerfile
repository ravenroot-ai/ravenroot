# Supply an IAM-approved immutable quay.io/keycloak/keycloak:<version>@sha256:<digest>.
ARG KEYCLOAK_IMAGE
FROM ${KEYCLOAK_IMAGE} AS build
ENV KC_DB=postgres KC_HEALTH_ENABLED=true KC_METRICS_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build
FROM ${KEYCLOAK_IMAGE}
COPY --from=build /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
