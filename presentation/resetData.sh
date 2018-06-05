#!/bin/sh

echo Delete the elastic search index...
curl -X DELETE http://localhost:9200/auction-items
echo

echo Deleting all Kafka data...
rm -rf target/lagom-dynamic-projects

echo Deleting all Cassandra data...
rm -rf target/embedded-cassandra

echo Restoring Cassandra data...
tar -xzf presentation/cassandra-backup.tgz -C target
