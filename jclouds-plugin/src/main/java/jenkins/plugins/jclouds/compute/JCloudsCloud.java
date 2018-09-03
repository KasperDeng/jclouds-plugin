/*
 * Copyright 2010-2016 Adrian Cole, Andrew Bayer, Fritz Elfert, Marat Mavlyutov, Monty Taylor, Vijay Kiran et. al.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jenkins.plugins.jclouds.compute;

import static org.jclouds.reflect.Reflection2.typeToken;

import java.io.Closeable;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
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
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.location.reference.LocationConstants;
import org.jclouds.logging.jdk.config.JDKLoggingModule;
import org.jclouds.openstack.nova.v2_0.NovaApi;
import org.jclouds.openstack.nova.v2_0.domain.Flavor;
import org.jclouds.openstack.nova.v2_0.domain.Quota;
import org.jclouds.openstack.nova.v2_0.features.FlavorApi;
import org.jclouds.providers.Providers;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.inject.Module;
import com.thoughtworks.xstream.converters.UnmarshallingContext;

import hudson.Extension;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import hudson.util.XStream2;
import jenkins.model.Jenkins;
import jenkins.plugins.jclouds.internal.CredentialsHelper;
import jenkins.plugins.jclouds.internal.SSHPublicKeyExtractor;
import jenkins.plugins.jclouds.modules.JenkinsConfigurationModule;

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

    public final String tenantId;

    /**
     * @deprecated Not used anymore, but retained for backward
     *             compatibility during deserialization.
     */
    private final transient String identity;
    /**
     * @deprecated Not used anymore, but retained for backward
     *             compatibility during deserialization.
     */
    private final transient Secret credential;

    public final String providerName;

    /**
     * @deprecated Not used anymore, but retained for backward
     *             compatibility during deserialization.
     */
    private final transient String privateKey;
    /**
     * @deprecated Not used anymore, but retained for backward
     *             compatibility during deserialization.
     */
    private final transient String publicKey; // NOPMD - unused private member

    public final String endPointUrl;
    public final String profile;
    private final int retentionTime;
    public int instanceCap;
    public final List<JCloudsSlaveTemplate> templates;
    public final int scriptTimeout;
    public final int startTimeout;
    private transient ComputeService compute;
    public final String zones;

    private String cloudGlobalKeyId;
    private String cloudCredentialsId;
    private String groupPrefix;
    private final boolean trustAll;
    private transient List<PhoneHomeMonitor> phms;

    static List<String> getCloudNames() {
        List<String> cloudNames = new ArrayList<String>();
        for (Cloud c : Jenkins.getInstance().clouds) {
            if (JCloudsCloud.class.isInstance(c)) {
                cloudNames.add(c.name);
            }
        }
        return cloudNames;
    }

    static JCloudsCloud getByName(String name) {
        return (JCloudsCloud) Jenkins.getInstance().clouds.getByName(name);
    }

    public String getCloudCredentialsId() {
        return cloudCredentialsId;
    }

    public boolean getTrustAll() {
        return trustAll;
    }

    public void setCloudCredentialsId(final String value) {
        cloudCredentialsId = value;
    }

    public String getCloudGlobalKeyId() {
        return cloudGlobalKeyId;
    }

    public void setCloudGlobalKeyId(final String value) {
        cloudGlobalKeyId = value;
    }

    public String getGlobalPrivateKey() {
        return getPrivateKeyFromCredential(cloudGlobalKeyId);
    }

    public String getGlobalPublicKey() {
        return getPublicKeyFromCredential(cloudGlobalKeyId);
    }

    public String getGroupPrefix() {
        return groupPrefix;
    }

    String prependGroupPrefix(final String name) {
        if (null == name) {
            return null;
        }
        String tmp = Util.fixEmptyAndTrim(groupPrefix);
        return tmp == null ? name : tmp + "-" + name;
    }

    private String removeGroupPrefix(final String name) {
        if (null == name) {
            return null;
        }
        String tmp = Util.fixEmptyAndTrim(groupPrefix);
        if (null == tmp) {
            return name;
        }
        tmp = tmp.concat("-");
        return name.startsWith(tmp) ? name.substring(tmp.length()) : name;
    }

    private String getPrivateKeyFromCredential(final String id) {
        if (!Strings.isNullOrEmpty(id)) {
            SSHUserPrivateKey supk = CredentialsMatchers
                    .firstOrNull(
                            CredentialsProvider.lookupCredentials(SSHUserPrivateKey.class, Jenkins.getInstance(),
                                    ACL.SYSTEM, Collections.<DomainRequirement> emptyList()),
                            CredentialsMatchers.withId(id));
            return CredentialsHelper.getPrivateKey(supk);
        }
        return "";
    }

    private String getPublicKeyFromCredential(final String id) {
        if (!Strings.isNullOrEmpty(id)) {
            try {
                return SSHPublicKeyExtractor.extract(getPrivateKeyFromCredential(id), null);
            } catch (IOException e) {
                LOGGER.warning(String.format("Error while extracting public key: %s", e));
            }
        }
        return "";
    }

    @DataBoundConstructor
    public JCloudsCloud(final String profile, final String providerName, final String tenantId,
            final String cloudCredentialsId, final String cloudGlobalKeyId, final String endPointUrl,
            final int instanceCap, final int retentionTime, final int scriptTimeout, final int startTimeout,
            final String zones, final String groupPrefix, final boolean trustAll,
            final List<JCloudsSlaveTemplate> templates) {
        super(Util.fixEmptyAndTrim(profile));
        this.profile = Util.fixEmptyAndTrim(profile);
        this.providerName = Util.fixEmptyAndTrim(providerName);
        this.tenantId = tenantId;
        this.identity = null; // Not used anymore, but retained for backward compatibility.
        this.credential = null; // Not used anymore, but retained for backward compatibility.
        this.privateKey = null; // Not used anymore, but retained for backward compatibility.
        this.publicKey = null; // Not used anymore, but retained for backward compatibility.
        this.cloudGlobalKeyId = Util.fixEmptyAndTrim(cloudGlobalKeyId);
        this.cloudCredentialsId = Util.fixEmptyAndTrim(cloudCredentialsId);
        this.endPointUrl = Util.fixEmptyAndTrim(endPointUrl);
        this.instanceCap = instanceCap;
        this.retentionTime = retentionTime;
        this.scriptTimeout = scriptTimeout;
        this.startTimeout = startTimeout;
        this.templates = Objects.firstNonNull(templates, Collections.<JCloudsSlaveTemplate>emptyList());
        this.zones = Util.fixEmptyAndTrim(zones);
        this.trustAll = trustAll;
        this.groupPrefix = groupPrefix;
        readResolve();
    }

    protected Object readResolve() {
        for (JCloudsSlaveTemplate template : templates) {
            template.cloud = this;
        }
        return this;
    }

    /**
     * Get the retention time in minutes or default value from
     * CloudInstanceDefaults if it is zero.
     *
     * @return The retention time in minutes.
     * @see CloudInstanceDefaults#DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES
     */
    public int getRetentionTime() {
        return retentionTime == 0 ? CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES : retentionTime;
    }

    private static final Iterable<Module> MODULES = ImmutableSet.<Module> of(new SshjSshClientModule(),
            new JDKLoggingModule() {
                @Override
                public org.jclouds.logging.Logger.LoggerFactory createLoggerFactory() {
            return new ComputeLogger.Factory();
        }
            }, new JenkinsConfigurationModule());

    private static <A extends Closeable> A api(Class<A> apitype, final String provider, final String credId,
            final Properties overrides) {
        // correct the classloader so that extensions can be found
        Thread.currentThread().setContextClassLoader(Apis.class.getClassLoader());
        return CredentialsHelper.setCredentials(ContextBuilder.newBuilder(provider), credId).overrides(overrides)
                .modules(MODULES).buildApi(typeToken(apitype));
    }

    private static Properties buildJcloudsOverrides(final String url, final String zones, boolean trustAll) {
        Properties ret = new Properties();
        if (!Strings.isNullOrEmpty(url)) {
            ret.setProperty(Constants.PROPERTY_ENDPOINT, url);
        }
        if (trustAll) {
            ret.put(Constants.PROPERTY_TRUST_ALL_CERTS, "true");
            ret.put(Constants.PROPERTY_RELAX_HOSTNAME, "true");
        }
        if (!Strings.isNullOrEmpty(zones)) {
            ret.setProperty(LocationConstants.PROPERTY_ZONES, zones);
        }
        return ret;
    }

    static <A extends Closeable> A api(Class<A> apitype, final String provider, final String credId, final String url,
            final String zones) {
        return api(apitype, provider, credId, buildJcloudsOverrides(url, zones, false));
    }

    static <A extends Closeable> A api(Class<A> apitype, final String provider, final String credId, final String url,
            final String zones, final boolean trustAll) {
        return api(apitype, provider, credId, buildJcloudsOverrides(url, zones, trustAll));
    }

    public <A extends Closeable> A newApi(Class<A> apitype) {
        Properties overrides = buildJcloudsOverrides(endPointUrl, zones, trustAll);
        if (scriptTimeout > 0) {
            overrides.setProperty(ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE, String.valueOf(scriptTimeout));
        }
        if (startTimeout > 0) {
            overrides.setProperty(ComputeServiceProperties.TIMEOUT_NODE_RUNNING, String.valueOf(startTimeout));
        }
        return api(apitype, providerName, cloudCredentialsId, overrides);
    }

    private static ComputeServiceContext ctx(final String provider, final String credId, final Properties overrides) {
        // correct the classloader so that extensions can be found
        Thread.currentThread().setContextClassLoader(Apis.class.getClassLoader());
        CredentialsHelper.setProject(credId, overrides);
        return CredentialsHelper.setCredentials(ContextBuilder.newBuilder(provider), credId).overrides(overrides)
                .modules(MODULES).buildView(ComputeServiceContext.class);
    }

    static ComputeServiceContext ctx(final String provider, final String credId, final String url, final String zones) {
        return ctx(provider, credId, buildJcloudsOverrides(url, zones, false));
    }

    static ComputeServiceContext ctx(final String provider, final String credId, final String url, final String zones,
            final boolean trustAll) {
        return ctx(provider, credId, buildJcloudsOverrides(url, zones, trustAll));
    }

    public ComputeService newCompute() {
        Properties overrides = buildJcloudsOverrides(endPointUrl, zones, trustAll);
        if (scriptTimeout > 0) {
            overrides.setProperty(ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE, String.valueOf(scriptTimeout));
        }
        if (startTimeout > 0) {
            overrides.setProperty(ComputeServiceProperties.TIMEOUT_NODE_RUNNING, String.valueOf(startTimeout));
        }
        return ctx(providerName, cloudCredentialsId, overrides).getComputeService();
    }

    public ComputeService getCompute() {
        if (this.compute == null) {
            this.compute = newCompute();
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
                LOGGER.info("Instance cap reached while adding capacity for label "
                        + ((label != null) ? label.toString() : "null"));
                break; // maxed out
            }

            final ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(this.name, template.name);

            plannedNodeList.add(new TrackedPlannedNode(provisioningId, template.getNumExecutors(),
                    Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                        @Override
                        public Node call() throws Exception {
                            LOGGER.finest("provisionSlave start");
                            // TODO: record the output somewhere
                            JCloudsSlave jcloudsSlave = template.provisionSlave(StreamTaskListener.fromStdout(),
                                    provisioningId);
                            Jenkins.getInstance().addNode(jcloudsSlave);
                            LOGGER.finest("provisionSlave done");

                            /*
                             * Cloud instances may have a long init script. If we declare the
                             * provisioning complete by returning without the connect operation,
                             * NodeProvisioner may decide that it still wants one more instance,
                             * because it sees that (1) all the slaves are offline (because it's
                             * still being launched) and (2) there's no capacity provisioned yet.
                             * Deferring the completion of provisioning until the launch goes
                             * successful prevents this problem.
                             */
                            ensureLaunched(jcloudsSlave);
                            return jcloudsSlave;
                        }
                    })));
            excessWorkload -= template.getNumExecutors();
        }
        return plannedNodeList;
    }

    private void ensureLaunched(JCloudsSlave jcloudsSlave) throws InterruptedException, ExecutionException {
        jcloudsSlave.waitForPhoneHome(null);
        Integer launchTimeoutSec;
        try {
            launchTimeoutSec = Integer.parseInt(System.getProperty("jclouds.plugin.launchTimeoutSec"));
        } catch (NumberFormatException e) {
            LOGGER.log(Level.WARNING, "Failed to get system property \"jclouds.plugin.launchTimeoutSec\"");
            launchTimeoutSec = 5 * 60; // default value, unit:second
        }

        Integer connectInterval;
        try {
            connectInterval = Integer.parseInt(System.getProperty("jclouds.plugin.connectInterval"));
        } catch (NumberFormatException e) {
            LOGGER.log(Level.WARNING, "Failed to get system property \"jclouds.plugin.connectInterval\"");
            connectInterval = 10;  // default value, unit:second
        }

        Computer computer = jcloudsSlave.toComputer();
        long startMoment = System.currentTimeMillis();
        while (null != computer && computer.isOffline()) {
            try {
                Thread.sleep(connectInterval * 1000L);
                LOGGER.info(String.format("Slave [%s] not connected yet", jcloudsSlave.getDisplayName()));
                computer.connect(false).get();
                //            } catch (InterruptedException e) {
                //                LOGGER.warning(String.format("Error while launching slave: %s", e));
                //            } catch (ExecutionException e) {
                //                Thread.sleep(5000l);
            } catch (InterruptedException | ExecutionException e) {
                LOGGER.warning(String.format("Error while launching slave: %s", e));
            }

            if ((System.currentTimeMillis() - startMoment) > 1000L * launchTimeoutSec) {
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
     * Gets {@link jenkins.plugins.jclouds.compute.JCloudsSlaveTemplate}
     * that has the matching {@link Label}.
     *
     * @param label The label to be matched.
     * @return The slave template or {@code null} if the specified label
     *         did not match.
     */
    public JCloudsSlaveTemplate getTemplate(Label label) {
        for (JCloudsSlaveTemplate t : templates)
            if (label == null || label.matches(t.getLabelSet()))
                return t;
        return null;
    }

    JCloudsSlave doProvisionFromTemplate(final JCloudsSlaveTemplate t) throws IOException {
        final StringWriter sw = new StringWriter();
        final StreamTaskListener listener = new StreamTaskListener(sw);
        final ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(this.name, t.name);
        JCloudsSlave node = t.provisionSlave(listener, provisioningId);
        Jenkins.getInstance().addNode(node);
        return node;
    }

    /**
     * Provisions a new node manually (by clicking a button in the
     * computer list)
     *
     * @param req {@link StaplerRequest}
     * @param rsp {@link StaplerResponse}
     * @param name Name of the template to provision
     * @throws ServletException if an error occurs.
     * @throws IOException if an error occurs.
     * @throws Descriptor.FormException if the form does not validate.
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
            JCloudsSlave node = doProvisionFromTemplate(t);
            rsp.sendRedirect2(req.getContextPath() + "/computer/" + node.getNodeName());
        } else {
            sendError("Instance cap for this cloud is now reached for cloud profile: " + profile + " for template type "
                    + name, req, rsp);
        }
    }

    /**
     * Determine how many nodes are currently running for this cloud.
     *
     * @return number of running nodes.
     */
    int getRunningNodesCount() {
        int nodeCount = 0;

        for (ComputeMetadata cm : getCompute().listNodes()) {
            if (NodeMetadata.class.isInstance(cm)) {
                NodeMetadata nm = (NodeMetadata) cm;
                String nodeGroup = removeGroupPrefix(nm.getGroup());

                if (getTemplate(nodeGroup) != null && !nm.getStatus().equals(NodeMetadata.Status.SUSPENDED)
                        && !nm.getStatus().equals(NodeMetadata.Status.TERMINATED)) {
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

    // TODO: 9/3/2018 Kasper need to upgrade jclouds version to 2.1.0 
    private boolean isExceedCloudQuota(JCloudsSlaveTemplate template) {
        //        ComputeService computeService = getCompute();
        //        LOGGER.finest("Jcloud-Plugin-Debug: get tenant from template: " + tenantId);
        //        Map<String, Integer> cloudQuota;
        //        try {
        //            cloudQuota = computeService.getQuotaByTenant(zones, tenantId);
        //        } catch (Exception e) {
        //            LOGGER.warning("Failed to get quota of cloud.\n" + e);
        //            return false;
        //        }
        //
        //        Map<String, Integer> totalUsage;
        //        try {
        //            totalUsage = computeService.getTotalUsageByTenant(zones, tenantId);
        //        } catch (Exception e) {
        //            LOGGER.warning("Failed to get total usage of tenant. \n" + e);
        //            return false;
        //        }
        //
        //        int plannedVcpu;
        //        int plannedRam;
        //
        //        Map<String, Integer> flavor = ImmutableMap.of(VCPU_KEY, 0, RAM_KEY, 0, INSTANCE_KEY, 0);
        //        if (Strings.isNullOrEmpty(template.hardwareId)) {
        //            plannedVcpu = ((Double) template.cores).intValue();
        //            plannedRam = template.ram;
        //        } else {
        //            String flavorId = template.hardwareId.split("/")[1];
        //            LOGGER.finest("Jcloud-Plugin-Debug: flavorId: " + flavorId);
        //            try {
        //                flavor = computeService.getFlavorByFlavorId(zones, flavorId);
        //                plannedVcpu = flavor.get(VCPU_KEY);
        //                plannedRam = flavor.get(RAM_KEY);
        //            } catch (Exception e) {
        //                LOGGER.warning("Failed to get flavor. \n" + e);
        //                // Use default value
        //                plannedVcpu = DEFAULT_VCPU;
        //                plannedRam = DEFAULT_RAM;
        //            }
        //        }
        //
        //        logResourceUsage(cloudQuota, flavor, totalUsage);
        //        LOGGER.finest("Jcloud-Plugin-Debug: planned vcpu:" + plannedVcpu + " planned ram: " + plannedRam);
        //
        //        if (totalUsage.get(VCPU_KEY) + plannedVcpu > cloudQuota.get(VCPU_KEY)) {
        //            logResourceExceed(VCPU_KEY, cloudQuota, totalUsage);
        //            return true;
        //        } else if (totalUsage.get(RAM_KEY) + plannedRam > cloudQuota.get(RAM_KEY)) {
        //            logResourceExceed(RAM_KEY, cloudQuota, totalUsage);
        //            return true;
        //        } else if (totalUsage.get(INSTANCE_KEY) + 1 > cloudQuota.get(INSTANCE_KEY)) {
        //            logResourceExceed(INSTANCE_KEY, cloudQuota, totalUsage);
        //            return true;
        //        }
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

    void registerPhoneHomeMonitor(final PhoneHomeMonitor monitor) {
        if (null == monitor) {
            throw new IllegalArgumentException("monitor may not be null");
        }
        if (null == phms) {
            phms = new CopyOnWriteArrayList<>();
        }
        phms.add(monitor);
    }

    public void unregisterPhoneHomeMonitor(final PhoneHomeMonitor monitor) {
        if (null == monitor) {
            throw new IllegalArgumentException("monitor may not be null");
        }
        if (null != phms) {
            phms.remove(monitor);
        }
    }

    void phoneHomeAbort() {
        if (null != phms) {
            for (final PhoneHomeMonitor monitor : phms) {
                monitor.interrupt();
            }
            phms.clear();
        }
    }

    public boolean phoneHomeNotify(final String name) {
        if (null != phms) {
            for (final PhoneHomeMonitor monitor : phms) {
                if (monitor.ring(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    void phoneHomeWaitAll() {
        if (null != phms) {
            for (final PhoneHomeMonitor monitor : phms) {
                monitor.join();
            }
            phms.clear();
        }
    }

    private static final Set<String> CONFIRMED_GZIP_SUPPORTERS = ImmutableSet.of("aws-ec2", "openstack-nova",
            "openstack-nova-ec2");

    public boolean allowGzippedUserData() {
        return !Strings.isNullOrEmpty(providerName) && CONFIRMED_GZIP_SUPPORTERS.contains(providerName);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {

        static boolean needSave = false;

        /**
         * Human readable name of this kind of configurable object.
         *
         * @return The human readable name of this object.
         */
        @Nonnull
        @Override
        public String getDisplayName() {
            return "Cloud (JClouds)";
        }

        public boolean isUserDataSupported(String provider, String credId, String url, String zones, boolean trustAll) {
            // GCE uses meta_data['user-data']
            if ("google-compute-engine".equals(provider)) {
                return true;
            }
            // Temporary hack for digitalocean2
            if ("digitalocean2".equals(provider)) {
                return true;
            }
            try (ComputeServiceContext ctx = ctx(provider, credId, url, zones, trustAll)) {
                TemplateOptions o = ctx.getComputeService().templateOptions();
                o.getClass().getMethod("userData", new byte[0].getClass());
            } catch (ReflectiveOperationException x) {
                return false;
            }

            return true;
        }

        public FormValidation doTestConnection(@QueryParameter String providerName,
                @QueryParameter String cloudCredentialsId, @QueryParameter String cloudGlobalKeyId,
                @QueryParameter String endPointUrl, @QueryParameter String zones, @QueryParameter boolean trustAll)
                throws IOException {
            if (null == Util.fixEmptyAndTrim(cloudCredentialsId)) {
                return FormValidation.error("Cloud credentials not specified.");
            }
            if (null == Util.fixEmptyAndTrim(cloudGlobalKeyId)) {
                return FormValidation.error("Cloud RSA key is not specified.");
            }

            // Remove empty text/whitespace from the fields.
            providerName = Util.fixEmptyAndTrim(providerName);
            endPointUrl = Util.fixEmptyAndTrim(endPointUrl);
            zones = Util.fixEmptyAndTrim(zones);

            FormValidation result = FormValidation.ok("Connection succeeded!");
            try (ComputeServiceContext ctx = ctx(providerName, cloudCredentialsId, endPointUrl, zones, trustAll)) {
                ctx.getComputeService().listNodes();
            } catch (Exception ex) {
                result = FormValidation.error("Cannot connect to specified cloud, please check the identity and credentials: " + ex.getMessage());
            }
            return result;
        }

        public FormValidation doCheckCloudGlobalKeyId(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        private ImmutableSortedSet<String> getAllProviders() {
            // correct the classloader so that jclouds extensions can be found
            Thread.currentThread().setContextClassLoader(Apis.class.getClassLoader());
            // TODO: apis need endpoints, providers don't; do something smarter
            // with this stuff :)
            Builder<String> builder = ImmutableSet.builder();
            builder.addAll(Iterables.transform(Apis.viewableAs(ComputeServiceContext.class), Apis.idFunction()));
            builder.addAll(
                    Iterables.transform(Providers.viewableAs(ComputeServiceContext.class), Providers.idFunction()));
            return ImmutableSortedSet.copyOf(builder.build());
        }

        public String defaultProviderName() {
            return getAllProviders().first();
        }

        public ListBoxModel doFillProviderNameItems() {
            ListBoxModel m = new ListBoxModel();
            for (final String supportedProvider : getAllProviders()) {
                // docker is not really usable with jclouds (yet?). Too many semantic differences.
                if (!"stub".equals(supportedProvider) && !"docker".equals(supportedProvider)) {
                    m.add(supportedProvider, supportedProvider);
                }
            }
            return m;
        }

        public AutoCompletionCandidates doAutoCompleteProviderName(@QueryParameter final String value) {
            // correct the classloader so that extensions can be found
            Thread.currentThread().setContextClassLoader(Apis.class.getClassLoader());
            // TODO: apis need endpoints, providers don't; do something smarter
            // with this stuff :)
            Builder<String> builder = ImmutableSet.builder();
            builder.addAll(Iterables.transform(Apis.viewableAs(ComputeServiceContext.class), Apis.idFunction()));
            builder.addAll(Iterables.transform(Providers.viewableAs(ComputeServiceContext.class), Providers.idFunction()));
            Iterable<String> supportedProviders = builder.build();

            Iterable<String> matchedProviders = Iterables.filter(supportedProviders, new Predicate<String>() {
                @Override
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

        public ListBoxModel doFillCloudCredentialsIdItems(@AncestorInPath ItemGroup context,
                @QueryParameter String currentValue) {
            if (!(context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getInstance())
                    .hasPermission(Computer.CONFIGURE)) {
                return new StandardUsernameListBoxModel().includeCurrentValue(currentValue);
            }
            return new StandardUsernameListBoxModel().includeAs(ACL.SYSTEM, context, StandardUsernameCredentials.class)
                    .includeCurrentValue(currentValue);
        }

        public ListBoxModel doFillCloudGlobalKeyIdItems(@AncestorInPath ItemGroup context,
                @QueryParameter String currentValue) {
            if (!(context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getInstance())
                    .hasPermission(Computer.CONFIGURE)) {
                return new StandardUsernameListBoxModel().includeCurrentValue(currentValue);
            }
            return new StandardUsernameListBoxModel().includeAs(ACL.SYSTEM, context, SSHUserPrivateKey.class)
                    .includeCurrentValue(currentValue);
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
                return FormValidation.error(e.getMessage());
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

        public FormValidation doCheckGroupPrefix(@QueryParameter String value) {
            if (!value.matches("^[a-z0-9]*$")) {
                return FormValidation.error("The group prefix may contain lowercase letters and numbers only.");
            }
            return FormValidation.ok();
        }

        @Initializer(after = InitMilestone.JOB_LOADED)
        public static void completed() throws IOException {
            if (needSave) {
                needSave = false;
                LOGGER.info(">>>>>> auto-saving migrated config data...");
                Jenkins.getInstance().save();
            }
        }
    }

    @Restricted(DoNotUse.class)
    public static class ConverterImpl extends XStream2.PassthruConverter<JCloudsCloud> {

        static final Logger LOGGER = Logger.getLogger(ConverterImpl.class.getName());

        public ConverterImpl(XStream2 xstream) {
            super(xstream);
        }

        @Override
        protected void callback(JCloudsCloud c, UnmarshallingContext context) {
            boolean any = false;
            if (Strings.isNullOrEmpty(c.getCloudGlobalKeyId()) && !Strings.isNullOrEmpty(c.privateKey)) {
                LOGGER.info("Upgrading config data: cloud global key -> via credentials plugin");
                c.setCloudGlobalKeyId(convertCloudPrivateKey(c.name, c.privateKey));
                any = true;
            }
            if (Strings.isNullOrEmpty(c.getCloudCredentialsId()) && !Strings.isNullOrEmpty(c.identity)) {
                LOGGER.info("Upgrading config data: cloud credentals -> via credentials plugin");
                final String description = "JClouds cloud " + c.name + " - auto-migrated";
                c.setCloudCredentialsId(CredentialsHelper.convertCredentials(description, c.identity, c.credential));
                any = true;
            }
            for (JCloudsSlaveTemplate t : c.templates) {
                if (t.upgrade()) {
                    any = true;
                }
            }
            if (any) {
                LOGGER.info(String.format(">>>>>> cloud %s needs saving migrated config data", c.name));
                ((JCloudsCloud.DescriptorImpl) c.getDescriptor()).needSave = true;
            }
        }

        /**
         * Converts the old privateKey into a new ssh-credential-plugin
         * record. The name of this cloud instance is used as username.
         *
         * @param name The name of the JCloudsCloud.
         * @param privateKey The old privateKey.
         * @return The Id of the newly created ssh-credential-plugin record.
         */
        private String convertCloudPrivateKey(final String name, final String privateKey) {
            final String description = "JClouds cloud " + name + " - auto-migrated";
            StandardUsernameCredentials u = new BasicSSHUserPrivateKey(CredentialsScope.SYSTEM, null, "Global key",
                    new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(privateKey), null, description);
            try {
                return CredentialsHelper.storeCredentials(u);
            } catch (IOException e) {
                LOGGER.warning(String.format("Error while migrating privateKey: %s", e.getMessage()));
            }
            return null;
        }
    }

}
