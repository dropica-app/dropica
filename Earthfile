# https://docs.earthly.dev/basics

VERSION 0.8

devbox:
  FROM jetpackio/devbox:latest

  # code generated using `devbox generate dockerfile`:
  # Installing your devbox project
  WORKDIR /code
  USER root:root
  RUN mkdir -p /code && chown ${DEVBOX_USER}:${DEVBOX_USER} /code
  USER ${DEVBOX_USER}:${DEVBOX_USER}
  COPY --chown=${DEVBOX_USER}:${DEVBOX_USER} devbox.json devbox.json
  COPY --chown=${DEVBOX_USER}:${DEVBOX_USER} devbox.lock devbox.lock
  RUN devbox run -- echo "Installed Packages."

test-migrations:
  FROM +devbox
  WORKDIR /code
  COPY schema.sql ./
  COPY backend/resources/migrations backend/resources/migrations
  COPY scripts/diff_schemas scripts/diff_schemas
  RUN devbox run -- scripts/diff_schemas

test-generate-query-code:
  FROM +devbox
  WORKDIR /code
  COPY schema.sql queries.sql queries_template.go.tmpl sqlc.yml .scalafmt.conf ./
  COPY backend/src/backend/queries/Queries.scala backend/src/backend/queries/
  RUN devbox run -- "sqlc vet && sqlc diff"

build-mill:
  FROM +devbox
  WORKDIR /code
  COPY +build-node-modules/node_modules/@shoelace-style/shoelace/dist ./node_modules/@shoelace-style/shoelace/dist
  COPY +build-node-modules/node_modules/emoji-picker-element/custom-elements.json ./node_modules/emoji-picker-element/custom-elements.json

  ENV CI=true
  COPY build.sc schema.sql schema.scala.ssp ./
  RUN devbox run -- mill __.compile # compile build setup
  COPY --dir rpc ./
  RUN devbox run -- mill 'rpc.{js,jvm}.compile'
  COPY --dir backend ./
  RUN devbox run -- mill backend.assembly
  COPY --dir frontend ./
  RUN devbox run -- mill frontend.fullLinkJS
  SAVE ARTIFACT out/backend/assembly.dest/out.jar backend.jar
  SAVE ARTIFACT out/frontend/fullLinkJS.dest frontend

build-node-modules:
  FROM +devbox
  WORKDIR /code
  COPY package.json bun.lockb ./
  RUN devbox run -- bun install
  SAVE ARTIFACT node_modules

build-vite:
  FROM +devbox
  WORKDIR /code
  COPY --dir +build-node-modules/node_modules ./
  COPY +build-mill/frontend ./out/frontend/fullLinkJS.dest
  COPY --dir main.js index.html vite.config.mts style.css public ./
  RUN devbox run -- bunx vite build
  SAVE ARTIFACT --keep-ts dist # timestamps must be kept for browser caching


build-docker:
  # FROM ghcr.io/graalvm/jdk-community:22
  FROM eclipse-temurin:21.0.3_9-jre-ubi9-minimal
  WORKDIR /app
  COPY +build-mill/backend.jar ./
  COPY --dir --keep-ts +build-vite/dist ./
  RUN mkdir -p /db
  ENV FRONTEND_DISTRIBUTION_PATH=dist
  ENV JDBC_URL=jdbc:sqlite:/db/data.db
  ENV JAVA_OPTS=" \
    -XX:InitialRAMPercentage=95 \
    -XX:MaxRAMPercentage=95"
  ENV JAVA_OPTS_DEBUG=" \
    -Dcom.sun.management.jmxremote=true \
    -Dcom.sun.management.jmxremote.port=9010 \
    -Dcom.sun.management.jmxremote.local.only=false \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.rmi.port=9010 \
    -Djava.rmi.server.hostname=localhost"
  # add $JAVA_OPTS_DEBUG after $JAVA_OPTS to be able to connect with a jmx debugger like visualvm
  CMD echo "starting jvm..." && java $JAVA_OPTS -jar backend.jar Migrate HttpServer
  SAVE IMAGE app:latest


app-deploy:
  # run locally:
  # FLY_API_TOKEN=$(flyctl tokens create deploy) earthly --allow-privileged --secret FLY_API_TOKEN -i +app-deploy --COMMIT_SHA=<xxxxxx>
  ARG --required COMMIT_SHA
  ARG --required FLY_APP_NAME
  ARG IMAGE="registry.fly.io/$FLY_APP_NAME:deployment-$COMMIT_SHA"
  FROM earthly/dind:alpine-3.19-docker-25.0.5-r0
  RUN apk add curl \
   && set -eo pipefail; curl -L https://fly.io/install.sh | sh
  COPY fly.toml ./
  WITH DOCKER --load $IMAGE=+build-docker
    RUN --secret FLY_API_TOKEN \
        docker image ls \
     && /root/.fly/bin/flyctl auth docker \
     && docker push $IMAGE \
     && /root/.fly/bin/flyctl deploy --image $IMAGE --build-arg COMMIT_SHA=$COMMIT_SHA
  END

scalafmt:
  FROM +devbox
  WORKDIR /code
  CACHE --chmod 0777 /home/devbox/.cache/coursier
  COPY --dir .scalafmt.conf backend frontend rpc ./
  RUN devbox run -- scalafmt --check

lint:
  BUILD +scalafmt

ci-test:
  BUILD +test-migrations
  BUILD +lint
  BUILD +build-mill
  BUILD +build-vite

ci-deploy:
  # To run manually:
  # FLY_API_TOKEN=$(flyctl tokens create deploy) earthly --allow-privileged --secret FLY_API_TOKEN +ci-deploy --COMMIT_SHA=$(git rev-parse HEAD)
  BUILD +ci-test
  BUILD +test-generate-query-code
  ARG --required COMMIT_SHA
  ARG --required FLY_APP_NAME
  BUILD +app-deploy --COMMIT_SHA=$COMMIT_SHA --FLY_APP_NAME=$FLY_APP_NAME
