package jenkins.plugins.jclouds.compute;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.domain.LoginCredentials;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.util.TimeUnit2;

/**
 * Jenkins Slave node - managed by JClouds.
 *
 * @author Vijay Kiran
 */
public class JCloudsSlave extends AbstractCloudSlave {
    private static final Logger LOGGER = Logger.getLogger(JCloudsSlave.class.getName());
    private transient NodeMetadata nodeMetaData;
    public final boolean stopOnTerminate;
    private final String cloudName;
    private String nodeId;
    private boolean pendingDelete;
    private boolean waitPhoneHome;
    //private final int overrideRetentionTime;
    private final int waitPhoneHomeTimeout;
    private final String user;
    private final String password;
    private final String privateKey;
    private final boolean authSudo;
    private final String jvmOptions;
    private final String credentialsId;

    private int overrideRetentionTime;
    private long terminatedMillTime;

    private String publicIpAddress = "";
    private Boolean isEmailNotified = false;

    @DataBoundConstructor
    @SuppressWarnings("rawtypes")
    public JCloudsSlave(String cloudName, String name, String nodeDescription, String remoteFS, String numExecutors, Mode mode, String labelString,
                        ComputerLauncher launcher, RetentionStrategy retentionStrategy, List<? extends NodeProperty<?>> nodeProperties, boolean stopOnTerminate,
                        int overrideRetentionTime, String user, String password, String privateKey, boolean authSudo, String jvmOptions, final boolean waitPhoneHome, final int waitPhoneHomeTimeout, final String credentialsId) throws Descriptor.FormException,
            IOException {
        super(name, nodeDescription, remoteFS, numExecutors, mode, labelString, launcher, retentionStrategy, nodeProperties);
        this.stopOnTerminate = stopOnTerminate;
        this.cloudName = cloudName;
        this.overrideRetentionTime = overrideRetentionTime;
        this.user = user;
        this.password = password;
        this.privateKey = privateKey;
        this.authSudo = authSudo;
        this.jvmOptions = jvmOptions;
        this.waitPhoneHome = waitPhoneHome;
        this.waitPhoneHomeTimeout = waitPhoneHomeTimeout;
        this.credentialsId = credentialsId;
    }

    /**
     * Constructs a new slave from JCloud's NodeMetadata
     *
     * @param cloudName             - the name of the cloud that's provisioning this slave.
     * @param fsRoot                - Location of Jenkins root (homedir) on the slave.
     * @param metadata              - JCloudsNodeMetadata
     * @param labelString           - Label(s) for this slave.
     * @param description           - Description of this slave.
     * @param numExecutors          - Number of executors for this slave.
     * @param stopOnTerminate       - if {@code true}, suspend the slave rather than terminating it.
     * @param overrideRetentionTime - Retention time to use specifically for this slave, overriding the cloud default.
     * @param jvmOptions            - Custom options for lauching the JVM on the slave.
     * @param waitPhoneHome         - if {@code true}, delay initial SSH connect until slave has "phoned home" back to jenkins.
     * @param waitPhoneHomeTimeout  - Timeout in minutes util giving up waiting for the "phone home" POST.
     * @param credentialsId         - Id of the credentials in Jenkin's global credentials database.
     * @throws IOException
     * @throws Descriptor.FormException
     */
    public JCloudsSlave(final String cloudName, final String fsRoot, NodeMetadata metadata, final String labelString,
            final String description, final String numExecutors, final boolean stopOnTerminate, final int overrideRetentionTime,
            String jvmOptions, final boolean waitPhoneHome, final int waitPhoneHomeTimeout, final String credentialsId) throws IOException, Descriptor.FormException {
        this(cloudName, metadata.getName(), description, fsRoot, numExecutors, Mode.EXCLUSIVE, labelString,
                new JCloudsLauncher(), new JCloudsRetentionStrategy(), Collections.<NodeProperty<?>>emptyList(),
                stopOnTerminate, overrideRetentionTime, metadata.getCredentials().getUser(),
                metadata.getCredentials().getPassword(), metadata.getCredentials().getPrivateKey(),
                metadata.getCredentials().shouldAuthenticateSudo(), jvmOptions, waitPhoneHome, waitPhoneHomeTimeout, credentialsId);
        this.nodeMetaData = metadata;
        this.nodeId = nodeMetaData.getId();
    }

    /**
     * Get Jclouds NodeMetadata associated with this Slave.
     *
     * @return {@link NodeMetadata}
     */
    public NodeMetadata getNodeMetaData() {
        if (this.nodeMetaData == null) {
            final ComputeService compute = JCloudsCloud.getByName(cloudName).getCompute();
            this.nodeMetaData = compute.getNodeMetadata(nodeId);
        }
        return nodeMetaData;
    }

    /**
     * Get Jclouds Custom JVM Options associated with this Slave.
     *
     * @return jvmOptions
     */
    public String getJvmOptions() {
        return jvmOptions;
    }

    /**
     * Get Jclouds LoginCredentials associated with this Slave.
     * <p/>
     * If Jclouds doesn't provide credentials, use stored ones.
     *
     * @return {@link LoginCredentials}
     */
    public LoginCredentials getCredentials() {
        LoginCredentials credentials = getNodeMetaData().getCredentials();
        if (credentials == null) {
            LOGGER.info("Using credentials from CloudSlave instance");
            credentials = LoginCredentials.builder().user(user).password(password).privateKey(privateKey).authenticateSudo(authSudo).build();
        } else {
            LOGGER.info("Using credentials from JClouds");
        }
        return credentials;
    }

    /**
     * Get the retention time for this slave, defaulting to the parent cloud's if not set.
     *
     * @return overrideTime
     */
    public int getRetentionTime() {
        if (overrideRetentionTime != 0) {
            return overrideRetentionTime;
        } else {
            return JCloudsCloud.getByName(cloudName).getRetentionTime();
        }
    }

    public void setOverrideRetentionTime(int overrideRetentionTime) {
        this.overrideRetentionTime = overrideRetentionTime;
        JCloudsUtility.saveNodesSettingToConfig();
    }

    public long getTerminatedMillTime() {
        return terminatedMillTime;
    }

    public void setTerminatedMillTime(long terminatedMillTime) {
        this.terminatedMillTime = terminatedMillTime;

        long extendTime = terminatedMillTime - System.currentTimeMillis();
        Long idleTime = System.currentTimeMillis() - getComputer().getIdleStartMilliseconds();
        Long idleMinTime = TimeUnit2.MILLISECONDS.toMinutes(idleTime);
        Long extendMinTime = TimeUnit2.MILLISECONDS.toMinutes(extendTime);
        setOverrideRetentionTime(idleMinTime.intValue() + extendMinTime.intValue());
        isEmailNotified = false;
        JCloudsUtility.saveNodesSettingToConfig();
    }

    public String getRetentionDay() {
        int retentionTime = getRetentionTime();
        if (retentionTime == -1) {
            return "Forever";
        } else {
            int day = retentionTime / JCloudsConstant.MIN_IN_DAY;
            int hour = (retentionTime % JCloudsConstant.MIN_IN_DAY)/60;
            int min = (retentionTime % JCloudsConstant.MIN_IN_DAY)%60;
            return Integer.toString(day) + " days " + Integer.toString(hour)
                    + " hours " + Integer.toString(min) + " mins";
        }
    }

    public long getRemainRetentionTime() {
        int retentionTime = getRetentionTime();
        if (retentionTime == -1) {
            return retentionTime;
        } else if (terminatedMillTime != 0) {
            return terminatedMillTime > System.currentTimeMillis() ? terminatedMillTime - System.currentTimeMillis() : 0;
        } else {
            long remainRetentionTime = TimeUnit2.MINUTES.toMillis(retentionTime) -
                    (System.currentTimeMillis() - getComputer().getIdleStartMilliseconds()) ;
            return remainRetentionTime > 0 ? remainRetentionTime : 0;
        }
    }

    public String getRemainRetentionDay() {
        int retentionTime = getRetentionTime();
        if (retentionTime == -1) {
            return "Forever";
        } else {
            int remainingTime;
            if ((terminatedMillTime != 0) && (terminatedMillTime >= System.currentTimeMillis())) {
                Long remaining = TimeUnit2.MILLISECONDS.toMinutes(terminatedMillTime - System.currentTimeMillis());
                remainingTime = remaining.intValue();
            } else {
                Long idleTime = TimeUnit2.MILLISECONDS.toMinutes(System.currentTimeMillis() -
                        getComputer().getIdleStartMilliseconds());
                remainingTime = retentionTime - idleTime.intValue();
            }
            int day = remainingTime / JCloudsConstant.MIN_IN_DAY;
            int hour = (remainingTime % JCloudsConstant.MIN_IN_DAY)/60;
            int min = (remainingTime % JCloudsConstant.MIN_IN_DAY)%60;
            return Integer.toString(day) + " days " + Integer.toString(hour)
                    + " hours " + Integer.toString(min) + " mins";
        }
    }

    public Boolean isEmailNotified() {
        return isEmailNotified;
    }

    public void setIsEmailNotified(Boolean isEmailNotified) {
        this.isEmailNotified = isEmailNotified;
        JCloudsUtility.saveNodesSettingToConfig();
    }

    public Boolean isOfflineOsInstance() {
        return JCloudsConstant.OFFLINE_LABEL.equals(getLabelString());
    }

    public String getNodeId() {
        return nodeId;
    }

    /**
     * Get the JClouds profile identifier for the Cloud associated with this slave.
     *
     * @return cloudName
     */
    public String getCloudName() {
        return cloudName;
    }

    public boolean isPendingDelete() {
        return pendingDelete;
    }

    public void setPendingDelete(boolean pendingDelete) {
        this.pendingDelete = pendingDelete;
    }

    public boolean isWaitPhoneHome() {
        return waitPhoneHome;
    }

    public void setWaitPhoneHome(boolean value) {
        waitPhoneHome = value;
    }

    public long getWaitPhoneHomeTimeoutMs() {
        if (0 < waitPhoneHomeTimeout) {
            return waitPhoneHomeTimeout * 60000;
        }
        return 0;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractCloudComputer<JCloudsSlave> createComputer() {
        LOGGER.info("Creating a new JClouds Slave");
        return new JCloudsComputer(this);
    }

    @Extension
    public static final class JCloudsSlaveDescriptor extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "JClouds Slave";
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isInstantiable() {
            return false;
        }
    }

    /**
     * Destroy the node calls {@link ComputeService#destroyNode}
     */
    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        final ComputeService compute = JCloudsCloud.getByName(cloudName).getCompute();
        if (compute.getNodeMetadata(nodeId) != null && compute.getNodeMetadata(nodeId).getStatus().equals(NodeMetadata.Status.RUNNING)) {
            if (stopOnTerminate) {
                LOGGER.info("Suspending the Slave : " + getNodeName());
                compute.suspendNode(nodeId);
            } else {
                LOGGER.info("Terminating the Slave : " + getNodeName());
                compute.destroyNode(nodeId);
            }
        } else {
            LOGGER.info("Slave " + getNodeName() + " is already not running.");
        }
    }

    public void waitForPhoneHome(PrintStream logger) throws InterruptedException {
        long timeout = System.currentTimeMillis() + getWaitPhoneHomeTimeoutMs();
        while (true) {
            long tdif = timeout - System.currentTimeMillis();
            if (tdif < 0) {
                throw new InterruptedException("wait for phone home timed out");
            }
            if (isPendingDelete()) {
                throw new InterruptedException("wait for phone home interrupted by delete request");
            }
            if (isWaitPhoneHome()) {
                final String msg = "Waiting for slave to phone home. " + tdif / 1000 + " seconds until timeout.";
                if (null != logger) {
                    logger.println(msg);
                } else {
                    LOGGER.info(msg);
                }
                Thread.sleep(30000);
            } else {
                break;
            }
        }
    }

    public String getPublicIpAddress() {
        return publicIpAddress;
    }

    public void setPublicIpAddress(String publicIpAddress) {
        this.publicIpAddress = publicIpAddress;
    }
}
