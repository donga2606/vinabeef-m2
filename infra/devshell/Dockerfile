# devshell: PowerShell 7 + docker CLI/compose, used by ./dev when pwsh is not installed on the host.
# It talks to the host Docker Engine through the mounted socket; it holds no project state.
FROM docker:28.5.1-cli AS docker

FROM debian:12.12-slim
ARG TARGETARCH
ARG PWSH_VERSION=7.5.4
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl libicu72 libssl3 \
 && rm -rf /var/lib/apt/lists/*
RUN arch="$([ "$TARGETARCH" = "arm64" ] && echo arm64 || echo x64)" \
 && curl -fsSL "https://github.com/PowerShell/PowerShell/releases/download/v${PWSH_VERSION}/powershell-${PWSH_VERSION}-linux-${arch}.tar.gz" -o /tmp/pwsh.tgz \
 && mkdir -p /opt/pwsh && tar -xzf /tmp/pwsh.tgz -C /opt/pwsh && rm /tmp/pwsh.tgz \
 && chmod +x /opt/pwsh/pwsh && ln -s /opt/pwsh/pwsh /usr/local/bin/pwsh
COPY --from=docker /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
ENV POWERSHELL_TELEMETRY_OPTOUT=1 DOTNET_CLI_TELEMETRY_OPTOUT=1
