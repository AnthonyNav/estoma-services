FROM postman/newman:6-alpine@sha256:d9b5e780ead0026bbb37f3759cd7b10fc8a88cd4030c8266a7c5e343d75a5198

COPY postman/platform-bff-e2e.postman_collection.json /opt/e2e/smoke.json
COPY postman/environment.example.json /opt/e2e/environment.example.json
COPY runner/run.sh /opt/e2e/run.sh

ENTRYPOINT ["/bin/sh", "/opt/e2e/run.sh"]
