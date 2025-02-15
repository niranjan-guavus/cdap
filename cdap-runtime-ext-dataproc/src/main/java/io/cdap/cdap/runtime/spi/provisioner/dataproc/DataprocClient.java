/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.runtime.spi.provisioner.dataproc;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.Throwables;
import com.google.api.gax.core.CredentialsProvider;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.NotFoundException;
import com.google.api.services.compute.Compute;
import com.google.api.services.compute.model.AccessConfig;
import com.google.api.services.compute.model.Firewall;
import com.google.api.services.compute.model.FirewallList;
import com.google.api.services.compute.model.Instance;
import com.google.api.services.compute.model.Network;
import com.google.api.services.compute.model.NetworkInterface;
import com.google.api.services.compute.model.NetworkList;
import com.google.api.services.compute.model.NetworkPeering;
import com.google.cloud.dataproc.v1.Cluster;
import com.google.cloud.dataproc.v1.ClusterConfig;
import com.google.cloud.dataproc.v1.ClusterControllerClient;
import com.google.cloud.dataproc.v1.ClusterControllerSettings;
import com.google.cloud.dataproc.v1.ClusterStatus;
import com.google.cloud.dataproc.v1.DeleteClusterRequest;
import com.google.cloud.dataproc.v1.DiskConfig;
import com.google.cloud.dataproc.v1.GceClusterConfig;
import com.google.cloud.dataproc.v1.GetClusterRequest;
import com.google.cloud.dataproc.v1.InstanceGroupConfig;
import com.google.cloud.dataproc.v1.SoftwareConfig;
import io.cdap.cdap.runtime.spi.provisioner.Node;
import io.cdap.cdap.runtime.spi.provisioner.RetryableProvisionException;
import io.cdap.cdap.runtime.spi.ssh.SSHPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Wrapper around the dataproc client that adheres to our configuration settings.
 */
final class DataprocClient implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(DataprocClient.class);
  // something like 2018-04-16T12:09:03.943-07:00
  private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss.SSSX");

  private final DataprocConf conf;
  private final ClusterControllerClient client;
  private final Compute compute;
  private final String projectId;
  private final String network;
  private final boolean useInternalIP;

  private enum PeeringState {
    ACTIVE,
    INACTIVE,
    NONE
  }

  static DataprocClient fromConf(DataprocConf conf,
                                 boolean privateInstance) throws IOException, GeneralSecurityException {
    ClusterControllerClient client = getClusterControllerClient(conf);
    Compute compute = getCompute(conf);

    String network = conf.getNetwork();
    String systemNetwork = null;
    try {
      systemNetwork = DataprocConf.getSystemNetwork();
    } catch (IllegalArgumentException e) {
      // expected when not running on GCP, ignore
    }

    String projectId = conf.getProjectId();
    String systemProjectId = null;
    try {
      systemProjectId = DataprocConf.getSystemProjectId();
    } catch (IllegalArgumentException e) {
      // expected when not running on GCP, ignore
    }
    if (network == null && projectId.equals(systemProjectId)) {
      // If the CDAP instance is running on a GCE/GKE VM from a project that matches the provisioner project,
      // use the network of that VM.
      network = systemNetwork;
    } else if (network == null) {
      // Otherwise, pick a network from the configured project using the Compute API
      network = findNetwork(projectId, compute);
    }
    if (network == null) {
      throw new IllegalArgumentException("Unable to automatically detect a network, please explicitly set a network.");
    }

    String subnet = conf.getSubnet();
    Network networkInfo = getNetworkInfo(projectId, network, compute);

    PeeringState state = getPeeringState(systemNetwork, systemProjectId, networkInfo);

    if (conf.isPreferExternalIP() && state == PeeringState.ACTIVE) {
      // Peering is setup between the system network and customer network and is in ACTIVE state.
      // However user has selected to preferred external IP the instance. This is not a private instance and is
      // capable of communicating with Dataproc cluster with external ip so just add warning message indicating that
      // internal IP can be used.
      LOG.info(String.format("VPC Peering from network '%s' in project '%s' to network '%s' " +
                               "in project '%s' is in the ACTIVE state. Prefer External IP can be set to false " +
                               "to launch Dataproc clusters with internal IP only.", systemNetwork,
                             systemProjectId, network, projectId));
    }

    // Use internal IP for the Dataproc cluster if instance is private or user has not preferred external IP and
    // (CDAP is running in the same customer project as Dataproc is going to be launched or
    // Network peering is done between customer network and system network and is in ACTIVE mode).
    boolean useInternalIP = privateInstance || !conf.isPreferExternalIP()
      && ((network.equals(systemNetwork) && projectId.equals(systemProjectId)) || state == PeeringState.ACTIVE);

    List<String> subnets = networkInfo.getSubnetworks();
    if (subnet != null && !subnetExists(subnets, subnet)) {
      throw new IllegalArgumentException(String.format("Subnet '%s' does not exist in network '%s' in project '%s'. "
                                                         + "Please use a different subnet.",
                                                       subnet, network, projectId));
    }

    // if the network uses custom subnets, a subnet must be provided to the dataproc api
    boolean autoCreateSubnet = networkInfo.getAutoCreateSubnetworks() == null ?
      false : networkInfo.getAutoCreateSubnetworks();
    if (!autoCreateSubnet) {
      // if the network uses custom subnets but none exist, error out
      if (subnets == null || subnets.isEmpty()) {
        throw new IllegalArgumentException(String.format("Network '%s' in project '%s' does not contain any subnets. "
                                                           + "Please create a subnet or use a different network.",
                                                         network, projectId));
      }

      // if no subnet was configured, choose one
      if (subnet == null) {
        subnet = chooseSubnet(network, subnets, conf.getRegion());
      }
    }

    return new DataprocClient(new DataprocConf(conf, network, subnet), client, compute, useInternalIP);
  }

  private static PeeringState getPeeringState(@Nullable String systemNetwork,
                                              String systemProjectId, Network networkInfo) {
    // systemNetwork can be null when CDAP is not running on GCP for example CDAP running in sandbox environment
    if (systemNetwork == null) {
      return PeeringState.NONE;
    }

    // note: vpc network is a global resource.
    // https://cloud.google.com/compute/docs/regions-zones/global-regional-zonal-resources#globalresources
    String systemNetworkPath = String.format("https://www.googleapis.com/compute/v1/projects/%s/global/networks/%s",
                                             systemProjectId, systemNetwork);

    LOG.info(String.format("Self link for the system network is %s", systemNetworkPath));
    List<NetworkPeering> peerings = networkInfo.getPeerings();
    // if the customer does not has a peering established at all the peering list is null
    if (peerings == null) {
      return PeeringState.NONE;
    }
    for (NetworkPeering peering : peerings) {
      if (!systemNetworkPath.equals(peering.getNetwork())) {
        continue;
      }
      return peering.getState().equals("ACTIVE") ? PeeringState.ACTIVE : PeeringState.INACTIVE;
    }
    return PeeringState.NONE;
  }

  private static boolean subnetExists(List<String> subnets, String subnet) {
    // subnets are of the form
    // "https://www.googleapis.com/compute/v1/projects/<project>/regions/<region>/subnetworks/<name>"
    // the provided subnet can be the full URI but is most often just the name
    for (String networkSubnet : subnets) {
      if (networkSubnet.equals(subnet) || networkSubnet.endsWith("subnetworks/" + subnet)) {
        return true;
      }
    }
    return false;
  }

  // subnets are identified as
  // "https://www.googleapis.com/compute/v1/projects/<project>/regions/<region>/subnetworks/<name>"
  // a subnet in the same region as the dataproc cluster must be chosen
  private static String chooseSubnet(String network, List<String> subnets, String region) {
    for (String subnet : subnets) {
      if (subnet.contains(region + "/subnetworks")) {
        return subnet;
      }
    }
    throw new IllegalArgumentException(
      String.format("Could not find any subnets in network '%s' that are for region '%s'. "
                      + "Please specify a subnet that is in the same selected region.",
                    network, region));
  }

  private static String findNetwork(String project, Compute compute) throws IOException {
    NetworkList networkList = compute.networks().list(project).execute();
    List<Network> networks = networkList.getItems();
    if (networks == null || networks.isEmpty()) {
      throw new IllegalArgumentException(String.format("Unable to find any networks in project '%s'. "
                                                         + "Please create a network in the project.", project));
    }
    return networks.iterator().next().getName();
  }

  private static Network getNetworkInfo(String project, String network, Compute compute) throws IOException {
    Network networkObj = compute.networks().get(project, network).execute();
    if (networkObj == null) {
      throw new IllegalArgumentException(String.format("Unable to find network '%s' in project '%s'. "
                                                         + "Please specify another network.", network, project));
    }
    return networkObj;
  }

  /**
   * Extracts and returns the zone name from the given full zone URI.
   */
  private static String getZone(String zoneUri) {
    int idx = zoneUri.lastIndexOf("/");
    if (idx <= 0) {
      throw new IllegalArgumentException("Invalid zone uri " + zoneUri);
    }
    return zoneUri.substring(idx + 1);
  }

  private static ClusterControllerClient getClusterControllerClient(DataprocConf conf) throws IOException {
    CredentialsProvider credentialsProvider = FixedCredentialsProvider.create(conf.getDataprocCredentials());

    String regionalEndpoint = conf.getRegion() + "-dataproc.googleapis.com:443";

    ClusterControllerSettings controllerSettings = ClusterControllerSettings.newBuilder()
      .setCredentialsProvider(credentialsProvider)
      .setEndpoint(regionalEndpoint)
      .build();
    return ClusterControllerClient.create(controllerSettings);
  }

  private static Compute getCompute(DataprocConf conf) throws GeneralSecurityException, IOException {
    HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    return new Compute.Builder(httpTransport, JacksonFactory.getDefaultInstance(), conf.getComputeCredential())
      .setApplicationName("cdap")
      .build();
  }

  private DataprocClient(DataprocConf conf, ClusterControllerClient client, Compute compute, boolean useInternalIP) {
    this.projectId = conf.getProjectId();
    this.network = conf.getNetwork();
    this.useInternalIP = useInternalIP;
    this.conf = conf;
    this.client = client;
    this.compute = compute;
  }

  /**
   * Create a cluster. This will return after the initial request to create the cluster is completed.
   * At this point, the cluster is likely not yet running, but in a provisioning state.
   *
   * @param name the name of the cluster to create
   * @param imageVersion the image version for the cluster
   * @param labels labels to set on the cluster
   * @throws InterruptedException if the thread was interrupted while waiting for the initial request to complete
   * @throws AlreadyExistsException if the cluster already exists
   * @throws IOException if there was an I/O error talking to Google Compute APIs
   * @throws RetryableProvisionException if there was a non 4xx error code returned
   */
  void createCluster(String name, String imageVersion,
                     Map<String, String> labels) throws RetryableProvisionException, InterruptedException, IOException {

    try {
      Map<String, String> metadata = new HashMap<>();
      SSHPublicKey publicKey = conf.getPublicKey();
      if (publicKey != null) {
        // Don't fail if there is no public key. It is for tooling case that the key might be generated differently.
        metadata.put("ssh-keys", publicKey.getUser() + ":" + publicKey.getKey());
      }
      // override any os-login that may be set on the project-level metadata
      metadata.put("enable-oslogin", "false");

      GceClusterConfig.Builder clusterConfig = GceClusterConfig.newBuilder()
        .addServiceAccountScopes(DataprocConf.CLOUD_PLATFORM_SCOPE)
        .putAllMetadata(metadata);

      if (conf.getZone() != null) {
        clusterConfig.setZoneUri(conf.getZone());
      }

      // subnets are unique within a location, not within a network, which is why these configs are mutually exclusive.
      if (conf.getSubnet() != null) {
        clusterConfig.setSubnetworkUri(conf.getSubnet());
      } else {
        clusterConfig.setNetworkUri(network);
      }

      for (String targetTag : getFirewallTargetTags()) {
        clusterConfig.addTags(targetTag);
      }

      // if internal ip is prefered then create dataproc cluster without external ip for better security
      if (useInternalIP) {
        clusterConfig.setInternalIpOnly(true);
      }

      Map<String, String> dataprocProps = new HashMap<>(conf.getDataprocProperties());
      // The additional property is needed to be able to provision a singlenode cluster on
      // dataproc. Dataproc has an issue that it will treat 0 number of worker
      // nodes as the default number, which means it will always provision a
      // cluster with 2 worker nodes if this property is not set. Refer to
      // https://cloud.google.com/dataproc/docs/concepts/configuring-clusters/single-node-clusters
      // for more information.
      dataprocProps.put("dataproc:dataproc.allow.zero.workers", "true");
      // Enable/Disable stackdriver
      dataprocProps.put("dataproc:dataproc.logging.stackdriver.enable",
                        Boolean.toString(conf.isStackdriverLoggingEnabled()));
      dataprocProps.put("dataproc:dataproc.monitoring.stackdriver.enable",
                        Boolean.toString(conf.isStackdriverMonitoringEnabled()));


      Cluster cluster = com.google.cloud.dataproc.v1.Cluster.newBuilder()
        .setClusterName(name)
        .putAllLabels(labels)
        .setConfig(ClusterConfig.newBuilder()
                     .setMasterConfig(InstanceGroupConfig.newBuilder()
                                        .setNumInstances(conf.getMasterNumNodes())
                                        .setMachineTypeUri(conf.getMasterMachineType())
                                        .setDiskConfig(DiskConfig.newBuilder()
                                                         .setBootDiskSizeGb(conf.getMasterDiskGB())
                                                         .setNumLocalSsds(0)
                                                         .build())
                                        .build())
                     .setWorkerConfig(InstanceGroupConfig.newBuilder()
                                        .setNumInstances(conf.getWorkerNumNodes())
                                        .setMachineTypeUri(conf.getWorkerMachineType())
                                        .setDiskConfig(DiskConfig.newBuilder()
                                                         .setBootDiskSizeGb(conf.getWorkerDiskGB())
                                                         .setNumLocalSsds(0)
                                                         .build())
                                        .build())
                     .setGceClusterConfig(clusterConfig.build())
                     .setSoftwareConfig(SoftwareConfig.newBuilder()
                                          .setImageVersion(imageVersion)
                                          .putAllProperties(dataprocProps))
                     .build())
        .build();

      client.createClusterAsync(projectId, conf.getRegion(), cluster).getInitialFuture().get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ApiException) {
        throw handleApiException((ApiException) cause);
      }
      throw Throwables.propagate(e);
    }
  }

  /**
   * Delete the specified cluster if it exists. This will return after the initial request to delete the cluster
   * is completed. At this point, the cluster is likely not yet deleted, but in a deleting state.
   *
   * @param name the name of the cluster to delete
   * @throws InterruptedException if the thread was interrupted while waiting for the initial request to complete
   * @throws RetryableProvisionException if there was a non 4xx error code returned
   */
  void deleteCluster(String name) throws RetryableProvisionException, InterruptedException {
    try {
      DeleteClusterRequest request = DeleteClusterRequest.newBuilder()
        .setClusterName(name)
        .setProjectId(projectId)
        .setRegion(conf.getRegion())
        .build();

      client.deleteClusterAsync(request).getInitialFuture().get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ApiException) {
        ApiException apiException = (ApiException) cause;
        if (apiException.getStatusCode().getCode().getHttpStatusCode() == 404) {
          // if the cluster was not found, it's ok that means it's deleted
          return;
        }
        throw handleApiException((ApiException) cause);
      }
      throw Throwables.propagate(e);
    }
  }

  /**
   * Get the status of the specified cluster.
   *
   * @param name the cluster name
   * @return the cluster status
   * @throws RetryableProvisionException if there was a non 4xx error code returned
   */
  io.cdap.cdap.runtime.spi.provisioner.ClusterStatus getClusterStatus(String name)
    throws RetryableProvisionException {
    return getDataprocCluster(name).map(cluster -> convertStatus(cluster.getStatus()))
      .orElse(io.cdap.cdap.runtime.spi.provisioner.ClusterStatus.NOT_EXISTS);
  }

  /**
   * Get information about the specified cluster. The cluster will not be present if it could not be found.
   *
   * @param name the cluster name
   * @return the cluster information if it exists
   * @throws RetryableProvisionException if there was a non 4xx error code returned
   */
  Optional<io.cdap.cdap.runtime.spi.provisioner.Cluster> getCluster(String name)
    throws RetryableProvisionException, IOException {
    Optional<Cluster> clusterOptional = getDataprocCluster(name);
    if (!clusterOptional.isPresent()) {
      return Optional.empty();
    }

    Cluster cluster = clusterOptional.get();
    String zone = getZone(cluster.getConfig().getGceClusterConfig().getZoneUri());

    List<Node> nodes = new ArrayList<>();
    for (String masterName : cluster.getConfig().getMasterConfig().getInstanceNamesList()) {
      nodes.add(getNode(compute, Node.Type.MASTER, zone, masterName));
    }
    for (String workerName : cluster.getConfig().getWorkerConfig().getInstanceNamesList()) {
      nodes.add(getNode(compute, Node.Type.WORKER, zone, workerName));
    }
    return Optional.of(new io.cdap.cdap.runtime.spi.provisioner.Cluster(
      cluster.getClusterName(), convertStatus(cluster.getStatus()), nodes, Collections.emptyMap()));
  }

  private Optional<Cluster> getDataprocCluster(String name) throws RetryableProvisionException {
    try {
      return Optional.of(client.getCluster(GetClusterRequest.newBuilder()
                                             .setClusterName(name)
                                             .setProjectId(projectId)
                                             .setRegion(conf.getRegion())
                                             .build()));
    } catch (NotFoundException e) {
      return Optional.empty();
    } catch (ApiException e) {
      if (e.getStatusCode().getCode().getHttpStatusCode() / 100 != 4) {
        // if there was an API exception that was not a 4xx, we can just try again
        throw new RetryableProvisionException(e);
      }
      // otherwise, it's not a retryable failure
      throw e;
    }
  }

  /**
   * Finds ingress firewall rules for the configured network that matches the required firewall port as
   * defined in {@link FirewallPort}.
   *
   * @return a {@link Collection} of tags that need to be added to the VM to have those firewall rules applies
   * @throws IOException If failed to discover those firewall rules
   */
  private Collection<String> getFirewallTargetTags() throws IOException {
    FirewallList firewalls = compute.firewalls().list(projectId).execute();
    List<String> tags = new ArrayList<>();
    Set<FirewallPort> requiredPorts = EnumSet.allOf(FirewallPort.class);

    // Iterate all firewall rules and see if it has ingress rules for all required firewall port.
    for (Firewall firewall : Optional.ofNullable(firewalls.getItems()).orElse(Collections.emptyList())) {
      // network is a url like https://www.googleapis.com/compute/v1/projects/<project>/<region>/networks/<name>
      // we want to get the last section of the path and compare to the configured network name
      int idx = firewall.getNetwork().lastIndexOf('/');
      String networkName = idx >= 0 ? firewall.getNetwork().substring(idx + 1) : firewall.getNetwork();
      if (!networkName.equals(network)) {
        continue;
      }

      String direction = firewall.getDirection();
      if (!"INGRESS".equals(direction) || firewall.getAllowed() == null) {
        continue;
      }

      for (Firewall.Allowed allowed : firewall.getAllowed()) {
        String protocol = allowed.getIPProtocol();
        boolean addTag = false;
        if ("ALL".equals(protocol)) {
          requiredPorts.clear();
          addTag = true;
        } else if ("tcp".equals(protocol)) {
          if (allowed.getPorts() == null || allowed.getPorts().contains(String.valueOf(FirewallPort.SSH.port))) {
            requiredPorts.remove(FirewallPort.SSH);
            addTag = true;
          }
        }
        if (addTag && firewall.getTargetTags() != null && !firewall.getTargetTags().isEmpty()) {
          tags.add(firewall.getTargetTags().iterator().next());
        }
      }
    }

    if (!requiredPorts.isEmpty()) {
      String portList = requiredPorts.stream().map(p -> String.valueOf(p.port)).collect(Collectors.joining(","));
      throw new IllegalArgumentException(String.format(
        "Could not find an ingress firewall rule for network '%s' for ports '%s'. " +
          "Please create a rule to allow incoming traffic on those ports for your IP range.",
        network, portList));
    }
    return tags;
  }

  private Node getNode(Compute compute, Node.Type type, String zone, String nodeName) throws IOException {
    Instance instance;
    try {
      instance = compute.instances().get(projectId, zone, nodeName).execute();
    } catch (GoogleJsonResponseException e) {
      // this can happen right after a cluster is created
      if (e.getStatusCode() == 404) {
        return new Node(nodeName, Node.Type.UNKNOWN, "", -1L, Collections.emptyMap());
      }
      throw e;
    }
    Map<String, String> properties = new HashMap<>();
    for (NetworkInterface networkInterface : instance.getNetworkInterfaces()) {
      Path path = Paths.get(networkInterface.getNetwork());
      String networkName = path.getFileName().toString();
      if (network.equals(networkName)) {
        // if the cluster does not have an external ip then then access config is null
        if (networkInterface.getAccessConfigs() != null) {
          for (AccessConfig accessConfig : networkInterface.getAccessConfigs()) {
            if (accessConfig.getNatIP() != null) {
              properties.put("ip.external", accessConfig.getNatIP());
              break;
            }
          }
        }
        properties.put("ip.internal", networkInterface.getNetworkIP());
      }
    }
    long ts;
    try {
      ts = DATE_FORMAT.parse(instance.getCreationTimestamp()).getTime();
    } catch (ParseException e) {
      ts = -1L;
    }
    String ip = properties.get("ip.external");
    if (useInternalIP) {
      ip = properties.get("ip.internal");
    }
    return new Node(nodeName, type, ip, ts, properties);
  }

  private io.cdap.cdap.runtime.spi.provisioner.ClusterStatus convertStatus(ClusterStatus status) {
    switch (status.getState()) {
      case ERROR:
        return io.cdap.cdap.runtime.spi.provisioner.ClusterStatus.FAILED;
      case RUNNING:
        return io.cdap.cdap.runtime.spi.provisioner.ClusterStatus.RUNNING;
      case CREATING:
        return io.cdap.cdap.runtime.spi.provisioner.ClusterStatus.CREATING;
      case DELETING:
        return io.cdap.cdap.runtime.spi.provisioner.ClusterStatus.DELETING;
      case UPDATING:
        // not sure if this is correct, or how it can get to updating state
        return io.cdap.cdap.runtime.spi.provisioner.ClusterStatus.RUNNING;
      default:
        // unrecognized and unknown
        return io.cdap.cdap.runtime.spi.provisioner.ClusterStatus.ORPHANED;
    }
  }

  @Override
  public void close() {
    client.close();
  }

  // if there was an API exception that was not a 4xx, we can just try again
  private RetryableProvisionException handleApiException(ApiException e) throws RetryableProvisionException {
    if (e.getStatusCode().getCode().getHttpStatusCode() / 100 != 4) {
      throw new RetryableProvisionException(e);
    }
    throw e;
  }

  /**
   * Firewall ports that we're concerned about.
   */
  private enum FirewallPort {
    SSH(22);

    private final int port;

    FirewallPort(int port) {
      this.port = port;
    }
  }
}
