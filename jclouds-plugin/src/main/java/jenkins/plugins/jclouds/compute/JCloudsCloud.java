package jenkins.plugins.jclouds.compute;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.servlet.ServletException;

import org.jclouds.Constants;
import org.jclouds.ContextBuilder;
import org.jclouds.apis.Apis;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.config.ComputeServiceProperties;
import org.jclouds.compute.domain.ComputeMetadata;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.enterprise.config.EnterpriseConfigurationModule;
import org.jclouds.location.reference.LocationConstants;
import org.jclouds.logging.jdk.config.JDKLoggingModule;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.domain.Flavor;
import org.jclouds.openstack.nova.v2_0.domain.Quota;
import org.jclouds.openstack.nova.v2_0.features.FlavorApi;
import org.jclouds.providers.Providers;
import org.jclouds.ssh.SshKeys;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.google.inject.Module;

import hudson.Extension;
import hudson.Util;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import shaded.com.google.common.base.Objects;
import shaded.com.google.common.base.Predicate;
import shaded.com.google.common.base.Strings;
import shaded.com.google.common.collect.ImmutableMap;
import shaded.com.google.common.collect.ImmutableSet;
import shaded.com.google.common.collect.ImmutableSet.Builder;
import shaded.com.google.common.collect.ImmutableSortedSet;
import shaded.com.google.common.collect.Iterables;
import shaded.com.google.common.io.Closeables;

/**
 * The JClouds version of the Jenkins Cloud.
 *
 * @author Vijay Kiran
 */
public class JCloudsCloud extends Cloud {

    static final Logger LOGGER = Logger.getLogger(JCloudsCloud.class.getName());

    static final String VCPU_KEY = "vcpu";
    static final String RAM_KEY = "ram";
    static final String INSTANCE_KEY = "instance";
    static final int DEFAULT_VCPU = 4;
    static final int DEFAULT_RAM = 8;

    public final String identity;
    public final Secret credential;
    public final String tenantId;
    public final String providerName;

    public final String privateKey;
    public final String publicKey;
    public final String endPointUrl;
    public final String profile;
    private final int retentionTime;
    public int instanceCap;
    public final List<JCloudsSlaveTemplate> templates;
    public final int scriptTimeout;
    public final int startTimeout;
    private transient ComputeService compute;
    public final String zones;

    public static List<String> getCloudNames() {
        List<String> cloudNames = new ArrayList<String>();
        for (Cloud c : Hudson.getInstance().clouds) {
            if (JCloudsCloud.class.isInstance(c)) {
                cloudNames.add(c.name);
            }
        }

        return cloudNames;
    }

    public static JCloudsCloud getByName(String name) {
        return (JCloudsCloud) Hudson.getInstance().clouds.getByName(name);
    }

    @DataBoundConstructor
    public JCloudsCloud(final String profile, final String providerName, final String identity, final String credential, final String tenantId, final String privateKey,
            final String publicKey, final String endPointUrl, final int instanceCap, final int retentionTime, final int scriptTimeout, final int startTimeout,
            final String zones, final List<JCloudsSlaveTemplate> templates) {
        super(Util.fixEmptyAndTrim(profile));
        this.profile = Util.fixEmptyAndTrim(profile);
        this.providerName = Util.fixEmptyAndTrim(providerName);
        this.identity = Util.fixEmptyAndTrim(identity);
        this.credential = Secret.fromString(credential);
        this.tenantId = Util.fixEmptyAndTrim(tenantId);
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.endPointUrl = Util.fixEmptyAndTrim(endPointUrl);
        this.instanceCap = instanceCap;
        this.retentionTime = retentionTime;
        this.scriptTimeout = scriptTimeout;
        this.startTimeout = startTimeout;
        this.templates = Objects.firstNonNull(templates, Collections.<JCloudsSlaveTemplate>emptyList());
        this.zones = Util.fixEmptyAndTrim(zones);
        readResolve();
    }

    protected Object readResolve() {
        for (JCloudsSlaveTemplate template : templates)
            template.cloud = this;
        return this;
    }

    /**
     * Get the retention time, defaulting to 30 minutes.
     */
    public int getRetentionTime() {
        return retentionTime == 0 ? 30 : retentionTime;
    }

    static final Iterable<Module> MODULES = ImmutableSet.<Module>of(new SshjSshClientModule(), new JDKLoggingModule() {
        @Override
        public org.jclouds.logging.Logger.LoggerFactory createLoggerFactory() {
            return new ComputeLogger.Factory();
        }
    }, new EnterpriseConfigurationModule());

    static ComputeServiceContext ctx(String providerName, String identity, String credential, String endPointUrl, String zones) {
        Properties overrides = new Properties();
        if (!Strings.isNullOrEmpty(endPointUrl)) {
            overrides.setProperty(Constants.PROPERTY_ENDPOINT, endPointUrl);
        }
        return ctx(providerName, identity, credential, overrides, zones);
    }

    static ComputeServiceContext ctx(String providerName, String identity, String credential, Properties overrides, String zones) {
        if (!Strings.isNullOrEmpty(zones)) {
            overrides.setProperty(LocationConstants.PROPERTY_ZONES, zones);
        }
        // correct the classloader so that extensions can be found
        Thread.currentThread().setContextClassLoader(Apis.class.getClassLoader());
        return ContextBuilder.newBuilder(providerName).credentials(identity, credential).overrides(overrides).modules(MODULES)
                .buildView(ComputeServiceContext.class);
    }

    public ComputeService getCompute() {
        if (this.compute == null) {
            Properties overrides = new Properties();
            if (!Strings.isNullOrEmpty(this.endPointUrl)) {
                overrides.setProperty(Constants.PROPERTY_ENDPOINT, this.endPointUrl);
            }
            if (scriptTimeout > 0) {
                overrides.setProperty(ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE, String.valueOf(scriptTimeout));
            }
            if (startTimeout > 0) {
                overrides.setProperty(ComputeServiceProperties.TIMEOUT_NODE_RUNNING, String.valueOf(startTimeout));
            }
            this.compute = ctx(this.providerName, this.identity, Secret.toString(credential), overrides, this.zones).getComputeService();
        }
        return compute;
    }

    public List<JCloudsSlaveTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        final JCloudsSlaveTemplate template = getTemplate(label);
        List<PlannedNode> plannedNodeList = new ArrayList<PlannedNode>();

        if (isExceedCloudQuota(template)) {
            LOGGER.info("The new planned node will cause quota exceed in cloud " + template.getCloud().getDisplayName());
            return plannedNodeList;
        }

        LOGGER.info("excessWorkload:" + excessWorkload + " instanceCap:" + instanceCap + " plannedNodeListSize:"
                + plannedNodeList.size() + " running nodes:" + getRunningNodesCount());

        while (excessWorkload > 0 && !Jenkins.getInstance().isQuietingDown() && !Jenkins.getInstance().isTerminating()) {

            if ((getRunningNodesCount() + plannedNodeList.size()) >= instanceCap) {
                LOGGER.info("Instance cap reached while adding capacity for label " + ((label != null) ? label.toString() : "null"));
                break; // maxed out
            }

            plannedNodeList.add(new PlannedNode(template.name, Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                public Node call() throws Exception {
                    LOGGER.finest("provisionSlave start");
                    // TODO: record the output somewhere
                    JCloudsSlave jcloudsSlave = template.provisionSlave(StreamTaskListener.fromStdout());
                    Jenkins.getInstance().addNode(jcloudsSlave);
                    LOGGER.finest("provisionSlave done");

                    /* Cloud instances may have a long init script. If we declare the provisioning complete by returning
                    without the connect operation, NodeProvisioner may decide that it still wants one more instance,
                    because it sees that (1) all the slaves are offline (because it's still being launched) and (2)
                    there's no capacity provisioned yet. Deferring the completion of provisioning until the launch goes
                    successful prevents this problem.  */
                    ensureLaunched(jcloudsSlave);
                    return jcloudsSlave;
                }
            }), Util.tryParseNumber(template.numExecutors, 1).intValue()));
            excessWorkload -= template.getNumExecutors();
        }
        return plannedNodeList;
    }

    private void ensureLaunched(JCloudsSlave jcloudsSlave) throws InterruptedException, ExecutionException {
        jcloudsSlave.waitForPhoneHome(null);
        Integer launchTimeoutSec = 5 * 60;
        Computer computer = jcloudsSlave.toComputer();
        long startMoment = System.currentTimeMillis();
        while (computer.isOffline()) {
            try {
                LOGGER.info(String.format("Slave [%s] not connected yet", jcloudsSlave.getDisplayName()));
                computer.connect(false).get();
                Thread.sleep(5000l);
            } catch (InterruptedException e) {
                LOGGER.warning(String.format("Error while launching slave: %s", e));
            } catch (ExecutionException e) {
                LOGGER.warning(String.format("Error while launching slave: %s", e));
            }

            if ((System.currentTimeMillis() - startMoment) > 1000l * launchTimeoutSec) {
                String message = String.format("Failed to connect to slave within timeout (%d s).", launchTimeoutSec);
                LOGGER.warning(message);
                throw new ExecutionException(new Throwable(message));
            }
        }
        LOGGER.info(String.format("The slave [%s] is ready to work now !!!", jcloudsSlave.getDisplayName()));
    }

    @Override
    public boolean canProvision(final Label label) {
        return getTemplate(label) != null;
    }

    public JCloudsSlaveTemplate getTemplate(String name) {
        for (JCloudsSlaveTemplate t : templates)
            if (t.name.equals(name))
                return t;
        return null;
    }

    /**
     * Gets {@link jenkins.plugins.jclouds.compute.JCloudsSlaveTemplate} that has the matching {@link Label}.
     */
    public JCloudsSlaveTemplate getTemplate(Label label) {
        for (JCloudsSlaveTemplate t : templates)
            if (label == null || label.matches(t.getLabelSet()))
                return t;
        return null;
    }

    /**
     * Provisions a new node manually (by clicking a button in the computer list)
     *
     * @param req  {@link StaplerRequest}
     * @param rsp  {@link StaplerResponse}
     * @param name Name of the template to provision
     * @throws ServletException
     * @throws IOException
     * @throws Descriptor.FormException
     */
    public void doProvision(StaplerRequest req, StaplerResponse rsp, @QueryParameter String name) throws ServletException, IOException,
            Descriptor.FormException {
        checkPermission(PROVISION);
        if (name == null) {
            sendError("The slave template name query parameter is missing", req, rsp);
            return;
        }
        JCloudsSlaveTemplate t = getTemplate(name);
        if (t == null) {
            sendError("No such slave template with name : " + name, req, rsp);
            return;
        }

        if (getRunningNodesCount() < instanceCap) {
            StringWriter sw = new StringWriter();
            StreamTaskListener listener = new StreamTaskListener(sw);
            JCloudsSlave node = t.provisionSlave(listener);
            Hudson.getInstance().addNode(node);
            rsp.sendRedirect2(req.getContextPath() + "/computer/" + node.getNodeName());
        } else {
            sendError("Instance cap for this cloud is now reached for cloud profile: " + profile + " for template type " + name, req, rsp);
        }
    }

    /**
     * Determine how many nodes are currently running for this cloud.
     */
    public int getRunningNodesCount() {
        int nodeCount = 0;

        for (ComputeMetadata cm : getCompute().listNodes()) {
            if (NodeMetadata.class.isInstance(cm)) {
                String nodeGroup = ((NodeMetadata) cm).getGroup();

                if (getTemplate(nodeGroup) != null && !((NodeMetadata) cm).getStatus().equals(NodeMetadata.Status.SUSPENDED)
                        && !((NodeMetadata) cm).getStatus().equals(NodeMetadata.Status.TERMINATED)) {
                    nodeCount++;
                }
            }
        }
        return nodeCount;
    }

    private class JCloudResource {
        public int vcpuAmount;
        public int ramAmount;
        public int runningNodeNum;
    }

    private Quota getQuotaByTenant(NovaApi novaApi, String zone, String tenant) {
        if (novaApi.getQuotaExtensionForZone(zone).isPresent()) {
            return novaApi.getQuotaExtensionForZone(zone).get().getByTenant(tenant);
        }
        return null;
    }

    private Flavor getFlavorByFlavorId(NovaApi novaApi, String zone, String flavorId) {
        FlavorApi flavorApi = novaApi.getFlavorApiForZone(zone);
        if (flavorApi != null) {
            return flavorApi.get(flavorId);
        }
        return null;
    }

    /**
     * Determine how many nodes are currently running for this cloud.
     */
    private JCloudResource getRunningNodesResource() {
        JCloudResource jCloudResource = new JCloudResource();

        for (ComputeMetadata cm : getCompute().listNodes()) {
            if (NodeMetadata.class.isInstance(cm)) {
                String nodeGroup = ((NodeMetadata) cm).getGroup();

                if (getTemplate(nodeGroup) != null && !((NodeMetadata) cm).getStatus().equals(NodeMetadata.Status.SUSPENDED)
                        && !((NodeMetadata) cm).getStatus().equals(NodeMetadata.Status.TERMINATED)) {
                    jCloudResource.vcpuAmount += ((NodeMetadata) cm).getHardware().getProcessors().size();
                    jCloudResource.ramAmount += ((NodeMetadata) cm).getHardware().getRam();
                    jCloudResource.runningNodeNum++;
                }
            }
        }
        return jCloudResource;
    }

    private boolean isExceedCloudQuota(JCloudsSlaveTemplate template) {
        ComputeService computeService = getCompute();
        LOGGER.finest("Jcloud-Plugin-Debug: get tenant from template: " + tenantId);
        Map<String, Integer> cloudQuota;
        try {
            cloudQuota = computeService.getQuotaByTenant(zones, tenantId);
        } catch (Exception e) {
            LOGGER.warning("Failed to get quota of cloud.\n" + e);
            return false;
        }

        Map<String, Integer> totalUsage;
        try {
            totalUsage = computeService.getTotalUsageByTenant(zones, tenantId);
        } catch (Exception e) {
            LOGGER.warning("Failed to get total usage of tenant. \n" + e);
            return false;
        }

        int plannedVcpu;
        int plannedRam;

        Map<String, Integer> flavor = ImmutableMap.of(VCPU_KEY, 0, RAM_KEY, 0, INSTANCE_KEY, 0);
        if (Strings.isNullOrEmpty(template.hardwareId)) {
            plannedVcpu = ((Double) template.cores).intValue();
            plannedRam = template.ram;
        } else {
            String flavorId = template.hardwareId.split("/")[1];
            LOGGER.finest("Jcloud-Plugin-Debug: flavorId: " + flavorId);
            try {
                flavor = computeService.getFlavorByFlavorId(zones, flavorId);
                plannedVcpu = flavor.get(VCPU_KEY);
                plannedRam = flavor.get(RAM_KEY);
            } catch (Exception e) {
                LOGGER.warning("Failed to get flavor. \n" + e);
                // Use default value
                plannedVcpu = DEFAULT_VCPU;
                plannedRam = DEFAULT_RAM;
            }
        }

        logResourceUsage(cloudQuota, flavor, totalUsage);
        LOGGER.finest("Jcloud-Plugin-Debug: planned vcpu:" + plannedVcpu + " planned ram: " + plannedRam);

        if (totalUsage.get(VCPU_KEY) + plannedVcpu > cloudQuota.get(VCPU_KEY)) {
            logResourceExceed(VCPU_KEY, cloudQuota, totalUsage);
            return true;
        } else if (totalUsage.get(RAM_KEY) + plannedRam > cloudQuota.get(RAM_KEY)) {
            logResourceExceed(RAM_KEY, cloudQuota, totalUsage);
            return true;
        } else if (totalUsage.get(INSTANCE_KEY) + 1 > cloudQuota.get(INSTANCE_KEY)) {
            logResourceExceed(INSTANCE_KEY, cloudQuota, totalUsage);
            return true;
        }
        return false;
    }

    private void logResourceUsage(Map<String, Integer> cloudQuota, Map<String, Integer> flavor,
            Map<String, Integer> totalUsage) {
        LOGGER.finest(String.format("[Jcloud-Plugin-Debug]: #cloudQuota#: %s quota: %d, %s quota: %d, %s, %d quota",
                VCPU_KEY, cloudQuota.get(VCPU_KEY),
                RAM_KEY, cloudQuota.get(RAM_KEY),
                INSTANCE_KEY, cloudQuota.get(INSTANCE_KEY)));

        LOGGER.finest(String.format("[Jcloud-Plugin-Debug]: #flavor#: %s quota: %d, %s quota: %d",
                VCPU_KEY, flavor.get(VCPU_KEY),
                RAM_KEY, flavor.get(RAM_KEY)));

        LOGGER.finest(String.format("[Jcloud-Plugin-Debug]: #totalUsage#: %s quota: %d, %s quota: %d, %s, %d quota",
                VCPU_KEY, totalUsage.get(VCPU_KEY),
                RAM_KEY, totalUsage.get(RAM_KEY),
                INSTANCE_KEY, totalUsage.get(INSTANCE_KEY)));
    }

    private void logResourceExceed(String key, Map<String, Integer> cloudQuota, Map<String, Integer> totalUsage) {
        LOGGER.info(String.format("The %s (current + planned = %d + %d) exceeds the quota %d",
                key, totalUsage.get(key), 1, cloudQuota.get(key)));
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        /**
         * Human readable name of this kind of configurable object.
         */
        @Override
        public String getDisplayName() {
            return "Cloud (JClouds)";
        }

        public FormValidation doTestConnection(@QueryParameter String providerName, @QueryParameter String identity, @QueryParameter String credential,
                @QueryParameter String privateKey, @QueryParameter String endPointUrl, @QueryParameter String zones) throws IOException {
            if (identity == null)
                return FormValidation.error("Invalid (AccessId).");
            if (credential == null)
                return FormValidation.error("Invalid credential (secret key).");
            if (privateKey == null)
                return FormValidation.error("Private key is not specified. Click 'Generate Key' to generate one.");

            // Remove empty text/whitespace from the fields.
            providerName = Util.fixEmptyAndTrim(providerName);
            identity = Util.fixEmptyAndTrim(identity);
            credential = Secret.fromString(credential).getPlainText();
            endPointUrl = Util.fixEmptyAndTrim(endPointUrl);
            zones = Util.fixEmptyAndTrim(zones);

            FormValidation result = FormValidation.ok("Connection succeeded!");
            ComputeServiceContext ctx = null;
            try {
                Properties overrides = new Properties();
                if (!Strings.isNullOrEmpty(endPointUrl)) {
                    overrides.setProperty(Constants.PROPERTY_ENDPOINT, endPointUrl);
                }

                ctx = ctx(providerName, identity, credential, overrides, zones);

                ctx.getComputeService().listNodes();
            } catch (Exception ex) {
                result = FormValidation.error("Cannot connect to specified cloud, please check the identity and credentials: " + ex.getMessage());
            } finally {
                Closeables.close(ctx, true);
            }
            return result;
        }

        public FormValidation doGenerateKeyPair(StaplerResponse rsp, String identity, String credential) throws IOException, ServletException {
            Map<String, String> keyPair = SshKeys.generate();
            rsp.addHeader("script", "findPreviousFormItem(button,'privateKey').value='" + keyPair.get("private").replace("\n", "\\n") + "';"
                    + "findPreviousFormItem(button,'publicKey').value='" + keyPair.get("public").replace("\n", "\\n") + "';");
            return FormValidation.ok("Successfully generated private Key!");
        }

        public FormValidation doCheckPrivateKey(@QueryParameter String value) throws IOException, ServletException {
            boolean hasStart = false, hasEnd = false;
            BufferedReader br = new BufferedReader(new StringReader(value));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals("-----BEGIN RSA PRIVATE KEY-----"))
                    hasStart = true;
                if (line.equals("-----END RSA PRIVATE KEY-----"))
                    hasEnd = true;
            }
            if (!hasStart)
                return FormValidation.error("Please make sure that the private key starts with '-----BEGIN RSA PRIVATE KEY-----'");
            if (!hasEnd)
                return FormValidation.error("The private key is missing the trailing 'END RSA PRIVATE KEY' marker. Copy&paste error?");
            if (SshKeys.fingerprintPrivateKey(value) == null)
                return FormValidation.error("Invalid private key, please check again or click on 'Generate Key' to generate a new key");
            return FormValidation.ok();
        }

        public ListBoxModel doFillProviderNameItems() {
            ListBoxModel m = new ListBoxModel();

            // correct the classloader so that extensions can be found
            Thread.currentThread().setContextClassLoader(Apis.class.getClassLoader());
            // TODO: apis need endpoints, providers don't; do something smarter
            // with this stuff :)
            Builder<String> builder = ImmutableSet.<String>builder();
            builder.addAll(Iterables.transform(Apis.viewableAs(ComputeServiceContext.class), Apis.idFunction()));
            builder.addAll(Iterables.transform(Providers.viewableAs(ComputeServiceContext.class), Providers.idFunction()));
            Iterable<String> supportedProviders = ImmutableSortedSet.copyOf(builder.build());

            for (String supportedProvider : supportedProviders) {
                m.add(supportedProvider, supportedProvider);
            }
            return m;
        }

        public AutoCompletionCandidates doAutoCompleteProviderName(@QueryParameter final String value) {
            // correct the classloader so that extensions can be found
            Thread.currentThread().setContextClassLoader(Apis.class.getClassLoader());
            // TODO: apis need endpoints, providers don't; do something smarter
            // with this stuff :)
            Builder<String> builder = ImmutableSet.<String>builder();
            builder.addAll(Iterables.transform(Apis.viewableAs(ComputeServiceContext.class), Apis.idFunction()));
            builder.addAll(Iterables.transform(Providers.viewableAs(ComputeServiceContext.class), Providers.idFunction()));
            Iterable<String> supportedProviders = builder.build();

            Iterable<String> matchedProviders = Iterables.filter(supportedProviders, new Predicate<String>() {
                public boolean apply(@Nullable String input) {
                    return input != null && input.startsWith(value.toLowerCase());
                }
            });

            AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            for (String matchedProvider : matchedProviders) {
                candidates.add(matchedProvider);
            }
            return candidates;
        }

        public FormValidation doCheckProfile(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckProviderName(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckPublicKey(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckCredential(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckIdentity(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        public FormValidation doCheckRetentionTime(@QueryParameter String value) {
            try {
                if (Integer.parseInt(value) == -1)
                    return FormValidation.ok();
            } catch (NumberFormatException e) {
            }
            return FormValidation.validateNonNegativeInteger(value);
        }

        public FormValidation doCheckScriptTimeout(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        public FormValidation doCheckStartTimeout(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        public FormValidation doCheckEndPointUrl(@QueryParameter String value) {
            if (!value.isEmpty() && !value.startsWith("http")) {
                return FormValidation.error("The endpoint must be an URL");
            }
            return FormValidation.ok();
        }
    }
}
