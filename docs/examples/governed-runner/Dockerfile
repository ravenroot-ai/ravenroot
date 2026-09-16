ARG BASE_IMAGE
FROM ${BASE_IMAGE}
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN mkdir -p /workspace && chown 65532:65532 /workspace
COPY reference_runtime.py /opt/reference_runtime.py
USER 65532:65532
WORKDIR /workspace
ENTRYPOINT ["python3", "/opt/reference_runtime.py"]
