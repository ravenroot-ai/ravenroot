ARG BASE_IMAGE
FROM ${BASE_IMAGE}
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN apk add --no-cache git
COPY disposable-repository/ /workspace/
RUN git -C /workspace init -b main \
 && git -C /workspace -c user.name=Fixture -c user.email=fixture@ravenroot.invalid add . \
 && git -C /workspace -c user.name=Fixture -c user.email=fixture@ravenroot.invalid commit -m 'Disposable acceptance repository' \
 && chown -R 65532:65532 /workspace
RUN cp -a /workspace /opt/workspace-seed
COPY agent_runtime.py /opt/agent_runtime.py
COPY security_acceptance.py /opt/security_acceptance.py
COPY kubernetes_attestation.py /opt/kubernetes_attestation.py
COPY kubernetes_pid_probe.c /opt/kubernetes_pid_probe.c
RUN apk add --no-cache --virtual .kubernetes-probe-build build-base \
 && cc -std=c11 -D_POSIX_C_SOURCE=200809L -O2 -Wall -Wextra -Werror -static \
      -o /opt/kubernetes_pid_probe /opt/kubernetes_pid_probe.c \
 && apk del .kubernetes-probe-build
USER 65532:65532
WORKDIR /workspace
ENTRYPOINT ["python3", "/opt/agent_runtime.py"]
