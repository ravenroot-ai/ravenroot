ARG BASE_IMAGE
FROM ${BASE_IMAGE}
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN apk add --no-cache git
COPY disposable-repository/ /workspace/
RUN git -C /workspace init -b main \
 && git -C /workspace -c user.name=Fixture -c user.email=fixture@ravenroot.invalid add . \
 && git -C /workspace -c user.name=Fixture -c user.email=fixture@ravenroot.invalid commit -m 'Disposable acceptance repository' \
 && chown -R 65532:65532 /workspace
COPY agent_runtime.py /opt/agent_runtime.py
COPY security_acceptance.py /opt/security_acceptance.py
USER 65532:65532
WORKDIR /workspace
ENTRYPOINT ["python3", "/opt/agent_runtime.py"]
