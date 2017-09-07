package org.sonar.application.process;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;

import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;

public class EsConnectorImpl implements EsConnector {
  @Override
  public ClusterHealthStatus getClusterHealthStatus(TransportClient transportClient) {
    return transportClient.admin().cluster()
      .health(new ClusterHealthRequest().waitForStatus(ClusterHealthStatus.YELLOW).timeout(timeValueSeconds(30)))
      .actionGet().getStatus();
  }
}
