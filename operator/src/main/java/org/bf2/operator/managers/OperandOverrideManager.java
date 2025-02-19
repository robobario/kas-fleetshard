package org.bf2.operator.managers;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.quarkus.runtime.Startup;
import org.bf2.common.ResourceInformerFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Startup
@ApplicationScoped
public class OperandOverrideManager {

    public static class OperandOverride {
        public String image;

        public List<EnvVar> env;

        @JsonIgnore
        private Map<String, Object> additionalProperties = new HashMap<>();

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public List<EnvVar> getEnv() {
            return env;
        }

        public void setEnv(List<EnvVar> env) {
            this.env = env;
        }

        @JsonAnyGetter
        public Map<String, Object> getAdditionalProperties() {
            return this.additionalProperties;
        }

        @JsonAnySetter
        public void setAdditionalProperty(String name, Object value) {
            this.additionalProperties.put(name, value);
        }

        public List<EnvVar> applyEnvironmentTo(List<EnvVar> originals) {
            Map<String, EnvVar> originalsOrderedMap = originals.stream().collect(Collectors.toMap(
                    EnvVar::getName,
                    v -> v,
                    (e1, e2) -> e1,
                    LinkedHashMap::new));

            Optional<List<EnvVar>> overrideEnv = Optional.ofNullable(env);
            overrideEnv.ifPresent(vars -> {
                vars.forEach(envVar -> {
                    if (envVar.getValue() == null && envVar.getValueFrom() == null) {
                        originalsOrderedMap.remove(envVar.getName());
                    } else {
                        originalsOrderedMap.put(envVar.getName(), envVar);
                    }
                });
            });


            return List.copyOf(originalsOrderedMap.values());
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Kafka extends OperandOverride {
        private Map<String, Object> brokerConfig = Map.of();

        public Map<String, Object> getBrokerConfig() {
            return brokerConfig;
        }

        public void setBrokerConfig(Map<String, Object> kafkaConfig) {
            this.brokerConfig = kafkaConfig;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Canary extends OperandOverride {
        public OperandOverride init = new OperandOverride();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OperandOverrides {
        public Canary canary = new Canary();
        @JsonProperty(value = "admin-server")
        public OperandOverride adminServer = new OperandOverride();

        @JsonProperty(value = "kafka")
        public Kafka kafka = new Kafka();

        @JsonProperty(value = "zookeeper")
        public OperandOverride zookeeper = new OperandOverride();

        @JsonProperty(value = "kafka-exporter")
        public OperandOverride kafkaExporter = new OperandOverride();
    }

    static final OperandOverrides EMPTY = new OperandOverrides();

    public static final String OPERANDS_YAML = "fleetshard_operands.yaml";

    private Map<String, OperandOverrides> overrides = new ConcurrentHashMap<>();

    @ConfigProperty(name = "image.admin-api")
    String adminApiImage;

    @ConfigProperty(name = "image.canary")
    String canaryImage;

    @ConfigProperty(name = "image.canary-init")
    String canaryInitImage;

    @ConfigProperty(name = "image.kafka")
    Optional<String> kafkaImage;

    @ConfigProperty(name = "image.zookeeper")
    Optional<String> zookeeperImage;

    @ConfigProperty(name = "image.kafka-exporter")
    Optional<String> kafkaExporterImage;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    @Inject
    InformerManager informerManager;

    @Inject
    StrimziManager strimziManager;

    @Inject
    Logger log;

    @PostConstruct
    protected void onStart() {
        this.resourceInformerFactory.create(ConfigMap.class,
                this.kubernetesClient.configMaps().inAnyNamespace().withLabel("app", "strimzi"),
                new ResourceEventHandler<ConfigMap>() {
                    @Override
                    public void onAdd(ConfigMap obj) {
                        updateOverrides(obj);
                    }

                    @Override
                    public void onDelete(ConfigMap obj, boolean deletedFinalStateUnknown) {
                        removeOverrides(obj);
                    }

                    @Override
                    public void onUpdate(ConfigMap oldObj, ConfigMap newObj) {
                        updateOverrides(newObj);
                    }
                });
    }

    private OperandOverrides getOverrides(String strimzi) {
        return overrides.getOrDefault(strimzi == null ? "" : strimzi, EMPTY);
    }

    public Canary getCanaryOverride(String strimzi) {
        return getOverrides(strimzi).canary;
    }

    public String getCanaryImage(String strimzi) {
        return getImage(getCanaryOverride(strimzi), strimzi, "canary").orElse(canaryImage);
    }

    public String getCanaryInitImage(String strimzi) {
        return getImage(getCanaryOverride(strimzi).init, strimzi, "canary-init").orElse(canaryInitImage);
    }

    public OperandOverride getAdminServerOverride(String strimzi) {
        return getOverrides(strimzi).adminServer;
    }

    public String getAdminServerImage(String strimzi) {
        return getImage(getAdminServerOverride(strimzi), strimzi, "admin-server").orElse(adminApiImage);
    }

    public Kafka getKafkaOverride(String strimzi) {
        return getOverrides(strimzi).kafka;
    }

    public Optional<String> getKafkaImage(String strimzi) {
        return getImage(getKafkaOverride(strimzi), strimzi, "kafka").or(() -> kafkaImage);
    }

    public OperandOverride getZookeeperOverride(String strimzi) {
        return getOverrides(strimzi).zookeeper;
    }

    public Optional<String> getZookeeperImage(String strimzi) {
        return getImage(getZookeeperOverride(strimzi), strimzi, "zookeeper").or(() -> zookeeperImage);
    }

    public OperandOverride getKafkaExporterOverride(String strimzi) { return getOverrides(strimzi).kafkaExporter; }

    public Optional<String> getKafkaExporterImage(String strimzi) {
        return getImage(getKafkaExporterOverride(strimzi), strimzi, "kafka-exporter").or(() -> kafkaExporterImage);
    }

    Optional<String> getImage(OperandOverride override, String strimzi, String componentName) {
        return Optional.ofNullable(override.getImage())
                .or(() -> {
                    if (strimzi != null) {
                        return Optional.ofNullable(strimziManager.getRelatedImage(strimzi, componentName));
                    }
                    return Optional.empty();
                });
    }

    void updateOverrides(ConfigMap obj) {
        String name = obj.getMetadata().getName();
        if (name.startsWith(StrimziManager.STRIMZI_CLUSTER_OPERATOR)) {
            String data = obj.getData().get(OPERANDS_YAML);
            log.infof("Updating overrides for %s to %s", name, data);
            boolean resync = false;
            if (data == null) {
                overrides.remove(name);
                resync = true;
            } else {
                OperandOverrides operands = Serialization.unmarshal(data, OperandOverrides.class);
                OperandOverrides old = overrides.put(name, operands);
                resync = old == null || !Serialization.asYaml(old).equals(Serialization.asYaml(operands));
            }
            if (resync) {
                informerManager.resyncManagedKafka();
            }
        }
    }

    void removeOverrides(ConfigMap obj) {
        String name = obj.getMetadata().getName();
        if (name.startsWith(StrimziManager.STRIMZI_CLUSTER_OPERATOR)) {
            log.infof("removing overrides for %s", name);
            overrides.remove(name);
            informerManager.resyncManagedKafka();
        }
    }

    void resetOverrides() {
        this.overrides.clear();
    }

}
