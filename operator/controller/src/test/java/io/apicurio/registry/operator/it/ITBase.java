package io.apicurio.registry.operator.it;

import io.apicurio.registry.operator.Constants;
import io.apicurio.registry.operator.api.v1.ApicurioRegistry3;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.quarkiverse.operatorsdk.runtime.QuarkusConfigurationService;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.TypeLiteral;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public abstract class ITBase {

    private static final Logger log = LoggerFactory.getLogger(ITBase.class);

    public static final String DEPLOYMENT_TARGET = "test.operator.deployment-target";
    public static final String OPERATOR_DEPLOYMENT_PROP = "test.operator.deployment";
    public static final String INGRESS_HOST_PROP = "test.operator.ingress-host";
    public static final String INGRESS_SKIP_PROP = "test.operator.ingress-skip";
    public static final String CLEANUP = "test.operator.cleanup";
    public static final String GENERATED_RESOURCES_FOLDER = "target/kubernetes/";
    public static final String CRD_FILE = "../model/target/classes/META-INF/fabric8/apicurioregistries3.registry.apicur.io-v1.yml";
    public static final String REMOTE_TESTS_INSTALL_FILE = "test.operator.install-file";

    public enum OperatorDeployment {
        local, remote
    }

    protected static OperatorDeployment operatorDeployment;
    protected static Instance<Reconciler<? extends HasMetadata>> reconcilers;
    protected static QuarkusConfigurationService configuration;
    protected static KubernetesClient client;
    protected static PortForwardManager portForwardManager;
    protected static IngressManager ingressManager;
    protected static String deploymentTarget;
    protected static String namespace;
    protected static boolean cleanup;
    private static Operator operator;

    @BeforeAll
    public static void before() throws Exception {
        configuration = CDI.current().select(QuarkusConfigurationService.class).get();
        reconcilers = CDI.current().select(new TypeLiteral<>() {
        });
        operatorDeployment = ConfigProvider.getConfig().getValue(OPERATOR_DEPLOYMENT_PROP,
                OperatorDeployment.class);
        deploymentTarget = ConfigProvider.getConfig().getValue(DEPLOYMENT_TARGET, String.class);
        cleanup = ConfigProvider.getConfig().getValue(CLEANUP, Boolean.class);

        setDefaultAwaitilityTimings();
        namespace = calculateNamespace();
        client = createK8sClient(namespace);
        createCRDs();
        createNamespace(client, namespace);

        portForwardManager = new PortForwardManager(client, namespace);
        ingressManager = new IngressManager(client, namespace);

        if (operatorDeployment == OperatorDeployment.remote) {
            createTestResources();
        } else {
            createOperator();
            registerReconcilers();
            operator.start();
        }
    }

    @BeforeEach
    public void beforeEach(TestInfo testInfo) {
        String testClassName = testInfo.getTestClass().map(c -> c.getSimpleName() + ".").orElse("");
        // spotless:off
        log.info("\n" +
                 "------- STARTING: {}{}\n" +
                 "------- Namespace: {}\n" +
                 "------- Mode: {}\n" +
                 "------- Deployment target: {}",
                testClassName, testInfo.getDisplayName(),
                namespace,
                ((operatorDeployment == OperatorDeployment.remote) ? "remote" : "local"),
                deploymentTarget);
        // spotless:on
    }

    static KubernetesClient createK8sClient(String namespace) {
        return new KubernetesClientBuilder()
                .withConfig(new ConfigBuilder(Config.autoConfigure(null)).withNamespace(namespace).build())
                .build();
    }

    private static List<HasMetadata> loadTestResources() throws IOException {
        var installFilePath = Path
                .of(ConfigProvider.getConfig().getValue(REMOTE_TESTS_INSTALL_FILE, String.class));
        var installFileRaw = Files.readString(installFilePath);
        // We're not editing the deserialized resources to replicate the user experience
        installFileRaw = installFileRaw.replace("PLACEHOLDER_NAMESPACE", namespace);
        return Serialization.unmarshal(installFileRaw);
    }

    private static void createTestResources() throws Exception {
        log.info("Creating generated resources into Namespace {}", namespace);
        loadTestResources().forEach(r -> {
            if ("minikube".equals(deploymentTarget) && r instanceof Deployment d) {
                // See https://stackoverflow.com/a/46101923
                d.getSpec().getTemplate().getSpec().getContainers()
                        .forEach(c -> c.setImagePullPolicy("IfNotPresent"));
            }
            client.resource(r).inNamespace(namespace).createOrReplace();
        });
    }

    private static void cleanTestResources() throws Exception {
        if (cleanup) {
            log.info("Deleting generated resources from Namespace {}", namespace);
            loadTestResources().forEach(r -> {
                client.resource(r).inNamespace(namespace).delete();
            });
        }
    }

    private static void createCRDs() {
        log.info("Creating CRDs");
        try {
            var crd = client.load(new FileInputStream(CRD_FILE));
            crd.createOrReplace();
            await().ignoreExceptions().until(() -> {
                crd.resources().forEach(r -> assertThat(r.get()).isNotNull());
                return true;
            });
        } catch (Exception e) {
            log.warn("Failed to create the CRD, retrying", e);
            createCRDs();
        }
    }

    private static void registerReconcilers() {
        log.info("Registering reconcilers for operator : {} [{}]", operator, operatorDeployment);

        for (Reconciler<?> reconciler : reconcilers) {
            log.info("Register and apply : {}", reconciler.getClass().getName());
            operator.register(reconciler);
        }
    }

    private static void createOperator() {
        operator = new Operator(configurationServiceOverrider -> {
            configurationServiceOverrider.withKubernetesClient(client);
        });
    }

    static void createNamespace(KubernetesClient client, String namespace) {
        log.info("Creating Namespace {}", namespace);
        client.resource(
                new NamespaceBuilder().withNewMetadata().addToLabels("app", "apicurio-registry-operator-test")
                        .withName(namespace).endMetadata().build())
                .create();
    }

    static String calculateNamespace() {
        return ("apicurio-registry-operator-test-" + UUID.randomUUID()).substring(0, 63);
    }

    static void setDefaultAwaitilityTimings() {
        Awaitility.setDefaultPollInterval(Duration.ofSeconds(5));
        Awaitility.setDefaultTimeout(Duration.ofSeconds(5 * 60));
    }

    @AfterEach
    public void cleanup() {
        if (cleanup) {
            log.info("Deleting CRs");
            client.resources(ApicurioRegistry3.class).delete();
            await().untilAsserted(() -> {
                var registryDeployments = client.apps().deployments().inNamespace(namespace)
                        .withLabels(Constants.BASIC_LABELS).list().getItems();
                assertThat(registryDeployments.size()).isZero();
            });
        }
    }

    @AfterAll
    public static void after() throws Exception {
        portForwardManager.stop();
        if (operatorDeployment == OperatorDeployment.local) {
            log.info("Stopping Operator");
            operator.stop();

            log.info("Creating new K8s Client");
            // create a new client bc operator has closed the old one
            client = createK8sClient(namespace);
        } else {
            cleanTestResources();
        }

        if (cleanup) {
            log.info("Deleting namespace : {}", namespace);
            assertThat(client.namespaces().withName(namespace).delete()).isNotNull();
        }
        client.close();
    }
}
