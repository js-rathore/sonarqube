package org.sonar.application.process;

import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;

public interface EsConnector {
  ClusterHealthStatus getClusterHealthStatus(TransportClient transportClient);
}
