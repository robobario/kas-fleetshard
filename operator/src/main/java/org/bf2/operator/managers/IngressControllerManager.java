package org.bf2.operator.managers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.kubernetes.client.utils.CachedSingleThreadScheduler;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.operator.v1.Config;
import io.fabric8.openshift.api.model.operator.v1.IngressController;
import io.fabric8.openshift.api.model.operator.v1.IngressControllerBuilder;
import io.fabric8.openshift.api.model.operator.v1.IngressControllerList;
import io.fabric8.openshift.api.model.operator.v1.IngressControllerSpec;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.zjsonpatch.JsonDiff;
import io.quarkus.arc.properties.UnlessBuildProperty;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaClusterSpec;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListener;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfiguration;
import org.bf2.common.OperandUtils;
import org.bf2.common.ResourceInformer;
import org.bf2.common.ResourceInformerFactory;
import org.bf2.operator.ManagedKafkaKeys;
import org.bf2.operator.ManagedKafkaKeys.Labels;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.operands.KafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaRoute;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Controls the resources and number of ingress replicas used by a managed kafka
 * <br>
 * This uses values from the actual Kafkas to determine ingress demand.
 * <br>
 * It will not reclaim excess replicas until there is a reduction in the number of nodes.
 */
@Startup
@ApplicationScoped
//excluding during smoke tests (when kafka=dev is set) running on Kubernetes
@UnlessBuildProperty(name = "kafka", stringValue = "dev", enableIfMissing = true)
public class IngressControllerManager {

    private static final int MIN_REPLICA_REDUCTION = 1;
    protected static final String INGRESSCONTROLLER_LABEL = "ingresscontroller.operator.openshift.io/owning-ingresscontroller";
    protected static final String HARD_STOP_AFTER_ANNOTATION = "ingress.operator.openshift.io/hard-stop-after";
    protected static final String MEMORY = "memory";
    protected static final String CPU = "cpu";

    /**
     * The node label identifying the AZ in which the node resides
     */
    public static final String TOPOLOGY_KEY = "topology.kubernetes.io/zone";

    protected static final String INGRESS_OPERATOR_NAMESPACE = "openshift-ingress-operator";

    protected static final String INGRESS_ROUTER_NAMESPACE = "openshift-ingress";

    protected static final String INFRA_NODE_LABEL = "node-role.kubernetes.io/infra";

    protected static final String WORKER_NODE_LABEL = "node-role.kubernetes.io/worker";

    /**
     * Domain part prefixed to domain reported on IngressController status. The CNAME DNS records
     * need to point to a sub-domain on the IngressController domain, so we just add this.
     */
    private static final String ROUTER_SUBDOMAIN = "ingresscontroller.";

    /**
     * Predicate that will return true if the input string looks like a broker resource name.
     */
    protected static final Predicate<String> IS_BROKER = Pattern.compile(".+-kafka-\\d+$").asMatchPredicate();
    private static final Predicate<Route> IS_BROKER_ROUTE = r -> IS_BROKER.test(r.getMetadata().getName());

    @Inject
    Logger log;

    @Inject
    OpenShiftClient openShiftClient;

    @Inject
    InformerManager informerManager;

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    private Map<String, String> routeMatchLabels = new ConcurrentHashMap<>();

    ResourceInformer<Pod> brokerPodInformer;
    ResourceInformer<Node> nodeInformer;
    ResourceInformer<IngressController> ingressControllerInformer;
    private boolean ready;

    //TODO: may need to differentiate between default and per az resources

    @ConfigProperty(name = "ingresscontroller.limit-cpu")
    Optional<Quantity> limitCpu;
    @ConfigProperty(name = "ingresscontroller.limit-memory")
    Optional<Quantity> limitMemory;
    @ConfigProperty(name = "ingresscontroller.request-cpu")
    Optional<Quantity> requestCpu;
    @ConfigProperty(name = "ingresscontroller.request-memory")
    Optional<Quantity> requestMemory;

    @ConfigProperty(name = "ingresscontroller.default-replica-count")
    Optional<Integer> defaultReplicaCount;
    @ConfigProperty(name = "ingresscontroller.az-replica-count")
    Optional<Integer> azReplicaCount;

    @ConfigProperty(name = "ingresscontroller.max-ingress-throughput")
    Quantity maxIngressThroughput;
    @ConfigProperty(name = "ingresscontroller.max-ingress-connections")
    int maxIngressConnections;
    @ConfigProperty(name = "ingresscontroller.hard-stop-after")
    String hardStopAfter;
    @ConfigProperty(name = "ingresscontroller.ingress-container-command")
    List<String> ingressContainerCommand;
    @ConfigProperty(name = "ingresscontroller.reload-interval-seconds")
    Integer ingressReloadIntervalSeconds;


    @ConfigProperty(name = "ingresscontroller.peak-throughput-percentage")
    int peakPercentage;

    private ResourceInformer<Deployment> deployments;
    private CachedSingleThreadScheduler scheduler = new CachedSingleThreadScheduler();
    private Set<String> deploymentsToReconcile = new HashSet<>();
    private ResourceRequirements deploymentResourceRequirements;

    public Map<String, String> getRouteMatchLabels() {
        return routeMatchLabels;
    }

    public void addToRouteMatchLabels(String key, String value) {
        routeMatchLabels.put(key, value);
    }

    public List<ManagedKafkaRoute> getManagedKafkaRoutesFor(ManagedKafka mk) {
        String multiZoneRoute = getIngressControllerDomain("kas");
        String bootstrapDomain = mk.getSpec().getEndpoint().getBootstrapServerHost();

        return Stream.concat(
                Stream.of(
                        new ManagedKafkaRoute("bootstrap", "", multiZoneRoute),
                        new ManagedKafkaRoute("admin-server", "admin-server", multiZoneRoute)),
                routesFor(mk)
                    .filter(IS_BROKER_ROUTE)
                    .map(r -> {
                        String router = getIngressControllerDomain("kas-" + getZoneForBrokerRoute(r));
                        String routePrefix = r.getSpec().getHost().replaceFirst("-" + bootstrapDomain, "");

                        return new ManagedKafkaRoute(routePrefix, routePrefix, router);
                    }))
                .sorted(Comparator.comparing(ManagedKafkaRoute::getName))
                .collect(Collectors.toList());
    }

    public String getClusterDomain() {
        return ingressControllerInformer.getList()
                .stream()
                .filter(ic -> "default".equals(ic.getMetadata().getName()))
                .map(ic -> ic.getStatus().getDomain())
                .findFirst()
                .orElse("apps.testing.domain.tld")
                .replaceFirst("apps.", "");
    }

    @PostConstruct
    protected void onStart() {
        NonNamespaceOperation<IngressController, IngressControllerList, Resource<IngressController>> ingressControllers =
                openShiftClient.operator().ingressControllers().inNamespace(INGRESS_OPERATOR_NAMESPACE);

        final FilterWatchListDeletable<Node, NodeList> workerNodeFilter = openShiftClient.nodes()
                .withLabel(WORKER_NODE_LABEL)
                .withoutLabel(INFRA_NODE_LABEL);

        nodeInformer = resourceInformerFactory.create(Node.class, workerNodeFilter, new ResourceEventHandler<HasMetadata>() {

            @Override
            public void onAdd(HasMetadata obj) {
                reconcileIngressControllers();
            }

            @Override
            public void onUpdate(HasMetadata oldObj, HasMetadata newObj) {
            }

            @Override
            public void onDelete(HasMetadata obj, boolean deletedFinalStateUnknown) {
                reconcileIngressControllers();
            }
        });

        FilterWatchListDeletable<Pod, PodList> brokerPodFilter = openShiftClient.pods().inAnyNamespace().withLabels(Map.of(
                OperandUtils.MANAGED_BY_LABEL, OperandUtils.STRIMZI_OPERATOR_NAME,
                OperandUtils.K8S_NAME_LABEL, "kafka"));

        brokerPodInformer = resourceInformerFactory.create(Pod.class, brokerPodFilter, new ResourceEventHandler<HasMetadata>() {

            @Override
            public void onAdd(HasMetadata obj) {
                reconcileIngressControllers();
            }

            @Override
            public void onUpdate(HasMetadata oldObj, HasMetadata newObj) {
            }

            @Override
            public void onDelete(HasMetadata obj, boolean deletedFinalStateUnknown) {
            }
        });

        ingressControllerInformer = resourceInformerFactory.create(IngressController.class, ingressControllers, new ResourceEventHandler<IngressController>() {

            @Override
            public void onAdd(IngressController obj) {
                reconcileIngressControllers();
            }

            @Override
            public void onUpdate(IngressController oldObj, IngressController newObj) {
                reconcileIngressControllers();
            }

            @Override
            public void onDelete(IngressController obj, boolean deletedFinalStateUnknown) {
                reconcileIngressControllers();
            }
        });

        ResourceRequirementsBuilder deploymentResourceBuilder = new ResourceRequirementsBuilder();
        limitCpu.ifPresent(quantity -> deploymentResourceBuilder.addToLimits(CPU, quantity));
        limitMemory.ifPresent(quantity -> deploymentResourceBuilder.addToLimits(MEMORY, quantity));
        requestCpu.ifPresent(quantity -> deploymentResourceBuilder.addToRequests(CPU, quantity));
        requestMemory.ifPresent(quantity -> deploymentResourceBuilder.addToRequests(MEMORY, quantity));

        // this is to patch the IngressController Router deployments to correctly size for resources, should be removed
        // after https://issues.redhat.com/browse/RFE-1475 is resolved.
        if (deploymentResourceBuilder.hasLimits() || deploymentResourceBuilder.hasRequests()) {
            this.deploymentResourceRequirements = deploymentResourceBuilder.build();
            deployments = this.resourceInformerFactory.create(Deployment.class,
                    this.openShiftClient.apps().deployments().inNamespace(INGRESS_ROUTER_NAMESPACE).withLabel(INGRESSCONTROLLER_LABEL),
                    new ResourceEventHandler<Deployment>() {
                        @Override
                        public void onAdd(Deployment deployment) {
                            patchIngressDeploymentResources(deployment);
                        }
                        @Override
                        public void onUpdate(Deployment oldDeployment, Deployment newDeployment) {
                            patchIngressDeploymentResources(newDeployment);
                        }
                        @Override
                        public void onDelete(Deployment deployment, boolean deletedFinalStateUnknown) {
                            // do nothing
                        }
            });
        }

        ready = true;
        reconcileIngressControllers();
    }

    private void patchIngressDeploymentResources(Deployment d) {
        if (!shouldReconcile(d)) {
            return;
        }

        synchronized (deploymentsToReconcile) {
            boolean needsReconcile = deploymentsToReconcile.isEmpty();
            deploymentsToReconcile.add(Cache.metaNamespaceKeyFunc(d));

            if (needsReconcile) {
                // delay the reconcile as we see clustered events
                scheduler.schedule(() -> {
                    Set<String> toReconcile;
                    synchronized (deploymentsToReconcile) {
                        toReconcile = new HashSet<>(deploymentsToReconcile);
                        deploymentsToReconcile.clear();
                    }
                    toReconcile.stream()
                            .map(deployments::getByKey)
                            .filter(Objects::nonNull)
                            .filter(this::shouldReconcile)
                            .forEach(this::doIngressPatch);
                }, 2, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * check first to see if an update is needed - if for whatever reason we get back a deployment with a non-schema field
     * 5.7 fabric8 has a bug that will not copy that in a builder, thus triggering an update via edit even if nothing really is changing
     * - that's fixed in ~5.11
     */
    private boolean shouldReconcile(Deployment d) {
        if (!OperandUtils.getOrDefault(d.getMetadata().getLabels(), INGRESSCONTROLLER_LABEL, "").startsWith("kas")) {
            return false;
        }
        List<Container> containers = d.getSpec().getTemplate().getSpec().getContainers();
        if (containers.size() != 1) {
            log.errorf("Wrong number of containers for Deployment %s/%s", d.getMetadata().getNamespace(), d.getMetadata().getName());
            return false;
        }
        Container ingressContainer = containers.get(0);
        return !(ingressContainer.getResources().equals(deploymentResourceRequirements) && Objects.equals(ingressContainer.getCommand(), ingressContainerCommand));
    }

    private void doIngressPatch(Deployment d) {
        log.infof("Updating the resource limits/container command for Deployment %s/%s", d.getMetadata().getNamespace(), d.getMetadata().getName());
        openShiftClient.apps().deployments().inNamespace(d.getMetadata().getNamespace())
            .withName(d.getMetadata().getName()).edit(
                new TypedVisitor<ContainerBuilder>() {
                    @Override
                    public void visit(ContainerBuilder element) {
                        element.withResources(deploymentResourceRequirements);
                        element.withCommand(ingressContainerCommand);
                    }
                });
    }

    @Scheduled(every = "3m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconcileIngressControllers() {
        if (!ready) {
            log.warn("One or more informers are not yet ready");
            return;
        }

        String defaultDomain = getClusterDomain();

        List<String> zones = nodeInformer.getList().stream()
                .filter(node -> node != null && node.getMetadata().getLabels() != null)
                .map(node -> node.getMetadata().getLabels().get(TOPOLOGY_KEY))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<String, IngressController> zoneToIngressController = new HashMap<>();
        zones.stream().forEach(z -> zoneToIngressController.put(z, ingressControllerInformer.getByKey(Cache.namespaceKeyFunc(INGRESS_OPERATOR_NAMESPACE, "kas-" + z))));

        // someday we might share access to the operator cache
        List<Kafka> kafkas = informerManager.getKafkas();

        long connectionDemand = kafkas.stream()
                .map(m -> m.getSpec().getKafka())
                .map(s -> s.getListeners()
                        .stream()
                        .filter(l -> AbstractKafkaCluster.EXTERNAL_LISTENER_NAME.equals(l.getName()))
                        .map(GenericKafkaListener::getConfiguration)
                        .map(GenericKafkaListenerConfiguration::getMaxConnections)
                        .filter(Objects::nonNull)
                        .map(c -> c * s.getReplicas())
                        .findFirst())
                .mapToLong(o -> o.orElse(0))
                .sum();

        ingressControllersFrom(zoneToIngressController, defaultDomain, kafkas, connectionDemand);

        buildDefaultIngressController(zones, defaultDomain, connectionDemand);

        if (deployments != null) {
            deployments.getList().stream().filter(this::shouldReconcile).forEach(this::doIngressPatch);
        }
    }

    private void createOrEdit(IngressController expected, IngressController existing) {
        String name = expected.getMetadata().getName();

        if (existing != null) {
            boolean needsApplied = shouldPatchIngressController(expected, existing);
            if (needsApplied) {
                openShiftClient.operator().ingressControllers()
                .inNamespace(expected.getMetadata().getNamespace())
                .withName(name)
                .edit(i -> new IngressControllerBuilder(i)
                        .editMetadata()
                        .withLabels(expected.getMetadata().getLabels())
                        .withAnnotations(expected.getMetadata().getAnnotations())
                        .endMetadata()
                        .withSpec(expected.getSpec())
                        .build());
            }
        } else {
            OperandUtils.createOrUpdate(openShiftClient.operator().ingressControllers(), expected);
        }
    }

    /**
     * the creation of the expected is dropping the additionalFields (fixed in fabric8 5.10+)
     * rather than patching all the time, we'll try to detect when the fields we care about have been changed / removed
     */
    boolean shouldPatchIngressController(IngressController expected, IngressController existing) {
        ObjectMapper objectMapper = Serialization.yamlMapper();
        JsonNode expectedJson = objectMapper.convertValue(expected, JsonNode.class);
        JsonNode actualJson = objectMapper.convertValue(existing, JsonNode.class);
        JsonNode patch = JsonDiff.asJson(expectedJson, actualJson);
        if (patch.isArray()) {
            int size = patch.size();
            for (int i = 0; i < size; i++) {
                JsonNode op = patch.get(i).get("op");
                if (op != null && !"add".equals(op.asText())) {
                    log.infof("Updating the existing IngressController %s/%s %s", expected.getMetadata().getNamespace(), expected.getMetadata().getName(), patch.toString());
                    return true;
                }
            }
        }
        return false;
    }

    private void ingressControllersFrom(Map<String, IngressController> ingressControllers, String clusterDomain, List<Kafka> kafkas, long connectionDemand) {
        LongSummaryStatistics egress = summarize(kafkas, KafkaCluster::getFetchQuota, () -> {throw new IllegalStateException("A kafka lacks a fetch quota");});
        LongSummaryStatistics ingress = summarize(kafkas, KafkaCluster::getProduceQuota, () -> {throw new IllegalStateException("A kafka lacks a produce quota");});

        // there is an assumption that the nodes / brokers will be balanced by zone
        double zonePercentage = 1d / ingressControllers.size();
        int replicas = numReplicasForZone(ingress, egress, connectionDemand, zonePercentage);
        ingressControllers.entrySet().stream().forEach(e -> {
            String zone = e.getKey();
            String kasZone = "kas-" + zone;
            String domain = kasZone + "." + clusterDomain;
            Map<String, String> routeMatchLabel = Map.of(ManagedKafkaKeys.forKey(kasZone), "true");
            LabelSelector routeSelector = new LabelSelector(null, routeMatchLabel);
            routeMatchLabels.putAll(routeMatchLabel);

            buildIngressController(kasZone, domain, e.getValue(), replicas, routeSelector, zone);
        });
    }

    private void buildDefaultIngressController(List<String> zones, String clusterDomain, long connectionDemand) {
        IngressController existing = ingressControllerInformer.getByKey(Cache.namespaceKeyFunc(INGRESS_OPERATOR_NAMESPACE, "kas"));

        int replicas = numReplicasForDefault(connectionDemand);

        final Map<String, String> routeMatchLabel = Map.of(Labels.KAS_MULTI_ZONE, "true");
        LabelSelector routeSelector = new LabelSelector(null, routeMatchLabel);
        routeMatchLabels.putAll(routeMatchLabel);

        buildIngressController("kas", "kas." + clusterDomain, existing, replicas, routeSelector, null);
    }

    private void buildIngressController(String name, String domain,
            IngressController existing, int replicas, LabelSelector routeSelector, String topologyValue) {

        Optional<IngressController> optionalExisting = Optional.ofNullable(existing);
        IngressControllerBuilder builder = optionalExisting.map(IngressControllerBuilder::new).orElseGet(IngressControllerBuilder::new);
        Integer existingReplicas = optionalExisting.map(IngressController::getSpec).map(IngressControllerSpec::getReplicas).orElse(null);

        // retain replicas as long as we're above the min reduction
        if (existingReplicas != null && existingReplicas - replicas <= MIN_REPLICA_REDUCTION) {
            replicas = Math.max(existingReplicas, replicas);
        }

        // enforce a minimum of two replicas on clusters that can accommodate it - which may change if we don't want to
        // provide pod / node level HA for the az specific replicas.
        if (replicas == 1 && nodeInformer.getList().size() > 3) {
            replicas = 2;
        }

        builder
            .editOrNewMetadata()
                .withName(name)
                .withNamespace(INGRESS_OPERATOR_NAMESPACE)
                .withLabels(OperandUtils.getDefaultLabels())
            .endMetadata()
            .editOrNewSpec()
                .withDomain(domain)
                .withRouteSelector(routeSelector)
                .withReplicas(replicas)
                .withNewEndpointPublishingStrategy()
                     .withType("LoadBalancerService")
                     .withNewLoadBalancer()
                         .withScope("External")
                         .withNewProviderParameters()
                             .withType("AWS")
                             .withNewAws()
                                 .withType("NLB")
                             .endAws()
                         .endProviderParameters()
                     .endLoadBalancer()
                .endEndpointPublishingStrategy()
            .endSpec();

        if (topologyValue != null && !topologyValue.isEmpty()) {
            builder
                .editSpec()
                    .withNewNodePlacement()
                        .editOrNewNodeSelector()
                            .addToMatchLabels(TOPOLOGY_KEY, topologyValue)
                            .addToMatchLabels(WORKER_NODE_LABEL, "")
                        .endNodeSelector()
                    .endNodePlacement()
                .endSpec();
        }

        if (hardStopAfter != null && !hardStopAfter.isBlank()) {
            builder.editMetadata().addToAnnotations(HARD_STOP_AFTER_ANNOTATION, hardStopAfter).endMetadata();
        } else {
            builder.editMetadata().removeFromAdditionalProperties(HARD_STOP_AFTER_ANNOTATION).endMetadata();
        }


        // intent here is to preserve any other UnsupportedConfigOverrides and just add/remove reloadInterval as necessary.
        // Surely there a better way to express this?
        var specNestedConfigUnsupportedConfigOverridesNested = builder.editSpec().withNewConfigUnsupportedConfigOverrides();
        HasMetadata current = builder.editSpec().buildUnsupportedConfigOverrides();
        if (current instanceof Config) {
            specNestedConfigUnsupportedConfigOverridesNested.addToAdditionalProperties(((Config) current).getAdditionalProperties());
        }
        if (ingressReloadIntervalSeconds > 0) {
            specNestedConfigUnsupportedConfigOverridesNested.addToAdditionalProperties("reloadInterval", ingressReloadIntervalSeconds);
        } else {
            specNestedConfigUnsupportedConfigOverridesNested.removeFromAdditionalProperties("reloadInterval");
        }
        specNestedConfigUnsupportedConfigOverridesNested.endConfigUnsupportedConfigOverrides().endSpec();

        createOrEdit(builder.build(), existing);
    }

    int numReplicasForZone(LongSummaryStatistics ingress, LongSummaryStatistics egress,
            long connectionDemand, double zonePercentage) {
        // use the override if present
        if (azReplicaCount.isPresent()) {
            return azReplicaCount.get();
        }

        long throughput = (egress.getMax() + ingress.getMax())/2;
        long replicationThroughput = ingress.getMax()*2;

        // subtract out that we could share the node with a broker + the 1Mi is padding to account for the bandwidth of other colocated pods
        // we assume a worst case that 1/2 of the traffic to this broker may come from another replicas
        long throughputPerIngressReplica = Quantity.getAmountInBytes(maxIngressThroughput).longValue()
                - replicationThroughput - throughput / 2 - Quantity.getAmountInBytes(Quantity.parse("1Mi")).longValue();

        if (throughputPerIngressReplica < 0) {
            throw new AssertionError("Cannot appropriately scale ingress as collocating with a broker takes more than the available node bandwidth");
        }

        // average of total ingress/egress in this zone
        double throughputDemanded = (egress.getSum() + ingress.getSum()) * zonePercentage / 2;

        // scale back with the assumption that we don't really need to meet the peak
        throughputDemanded *= peakPercentage / 100D;

        int replicaCount = (int)Math.ceil(throughputDemanded / throughputPerIngressReplica);
        int connectionReplicaCount = numReplicasForConnectionDemand((long) (connectionDemand * zonePercentage));

        return Math.max(1, Math.max(connectionReplicaCount, replicaCount));
    }

    static LongSummaryStatistics summarize(List<Kafka> kafkas, Function<Kafka, String> quantity,
            Supplier<String> defaultValue) {
        return kafkas.stream()
                .flatMap(m -> {
                    KafkaClusterSpec s = m.getSpec().getKafka();
                    String value = Optional.of(quantity.apply(m)).orElseGet(defaultValue);
                    return Collections.nCopies(s.getReplicas(), Quantity.getAmountInBytes(Quantity.parse(value))).stream();
                })
                .mapToLong(BigDecimal::longValue)
                .summaryStatistics();
    }

    int numReplicasForDefault(long connectionDemand) {
        // use the override if present
        if (defaultReplicaCount.isPresent()) {
            return defaultReplicaCount.get();
        }

        /*
         * an assumption here is that these ingress replicas will not become bandwidth constrained - but that may need further qualification
         */
        return numReplicasForConnectionDemand(connectionDemand);
    }

    private int numReplicasForConnectionDemand(double connectionDemand) {
        return (int)Math.ceil(connectionDemand / maxIngressConnections);
    }

    private String getIngressControllerDomain(String ingressControllerName) {
        return ingressControllerInformer.getList().stream()
                .filter(ic -> ic.getMetadata().getName().equals(ingressControllerName))
                .map(ic -> ROUTER_SUBDOMAIN + (ic.getStatus() != null ? ic.getStatus().getDomain() : ic.getSpec().getDomain()))
                .findFirst()
                .orElse("");
    }

    private Stream<Route> routesFor(ManagedKafka managedKafka) {
        return informerManager.getRoutesInNamespace(managedKafka.getMetadata().getNamespace())
                .filter(route -> isOwnedBy(route, Kafka.RESOURCE_KIND, AbstractKafkaCluster.kafkaClusterName(managedKafka), AbstractKafkaCluster.kafkaClusterNamespace(managedKafka))
                        || isOwnedBy(route, managedKafka.getKind(), managedKafka.getMetadata().getName(), managedKafka.getMetadata().getNamespace()));
    }

    private String getZoneForBrokerRoute(Route route) {
        String serviceName = route.getSpec().getTo().getName();
        String namespace = route.getMetadata().getNamespace();
        Service svc = informerManager.getLocalService(namespace, serviceName);
        if (svc == null) {
            return "";
        }

        Map<String, String> labels = svc.getSpec().getSelector();
        Stream<Pod> pods = brokerPodInformer.getList().stream()
                .filter(p -> p.getMetadata().getNamespace().equals(namespace)
                        && p.getMetadata().getLabels().entrySet().containsAll(labels.entrySet()));

        return pods
                .findFirst()
                .map(p -> p.getSpec().getNodeName())
                .map(nodeInformer::getByKey)
                .map(n -> n.getMetadata().getLabels().get(IngressControllerManager.TOPOLOGY_KEY))
                .orElse("");
    }

    private boolean isOwnedBy(HasMetadata owned, String ownerKind, String ownerName, String ownerNamespace) {
        boolean sameNamespace = ownerNamespace.equals(owned.getMetadata().getNamespace());
        return sameNamespace &&
                owned.getMetadata().getOwnerReferences().stream()
                    .anyMatch(ref -> ref.getKind().equals(ownerKind) && ref.getName().equals(ownerName));
    }
}
