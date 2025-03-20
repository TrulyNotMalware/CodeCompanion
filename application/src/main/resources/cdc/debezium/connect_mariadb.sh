#!/bin/bash

# Example mariadb with Debezium.

URL="http://debezium_hostname:port/connectors"
CONTENT_TYPE="application/json"

read -r -d '' PAYLOAD << EOF
{
  "name": "mariadb-event-connector",
  "config": {
    "connector.class": "io.debezium.connector.mariadb.MariaDbConnector",

    "database.hostname": "mariadb",
    "database.port": "3306",
    "database.user": "DATABASE_USER_ID",
    "database.password": "DATABASE_PASSWD",
    "database.ssl.mode":"disabled",
    "database.server.id": "DATABASE_SERVER_ID",
    "database.server.name": "mariadb",
    "database.include.list": "code_companion",
    "table.include.list": "code_companion.outbox_message",
    "topic.prefix": "cdc",
    "schema.history.internal.kafka.bootstrap.servers": "kafka_host:port",
    "schema.history.internal.kafka.topic": "schema-history.code_companion.outbox_message",
    "column.propagate.source.type": "true",
    "schema.history.internal.store.only.captured.tables.ddl": "true"
  }
}
EOF

curl -X POST "${URL}" \
    -H "Content-Type: ${CONTENT_TYPE}" \
    -d "${PAYLOAD}"