FROM docker.io/library/eclipse-temurin:25-jdk

RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    curl \
    ca-certificates \
    python3 \
    python3-venv \
    python3-pip \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m venv /opt/venv && /opt/venv/bin/pip install --upgrade pip

ENV ARCHIPELOBBY_PYTHON_EXECUTABLE=/opt/venv/bin/python

USER root
