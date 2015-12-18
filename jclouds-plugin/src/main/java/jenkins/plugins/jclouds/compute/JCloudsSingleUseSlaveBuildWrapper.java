package jenkins.plugins.jclouds.compute;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

import org.jclouds.compute.ComputeService;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.slaves.OfflineCause;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.ReflectionUtils;
import shaded.com.google.common.base.Strings;

public class JCloudsSingleUseSlaveBuildWrapper extends BuildWrapper {
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(JCloudsSingleUseSlaveBuildWrapper.class.getName());

    @DataBoundConstructor
    public JCloudsSingleUseSlaveBuildWrapper() {

    }

    @Override
    public Environment setUp(final AbstractBuild build, Launcher launcher, final BuildListener listener) {
        LOGGER.info("Debug: Start single-use slave Extension setup");

        if (JCloudsComputer.class.isInstance(build.getExecutor().getOwner())) {
            // Get current running node
            final JCloudsComputer c = (JCloudsComputer) build.getExecutor().getOwner();
            final JCloudsCloud jcloudsCloud = JCloudsCloud.getByName(c.getCloudName());
            final JCloudsSlave jcloudsSlave = c.getNode();
            final String nodeId = jcloudsSlave.getNodeId();
            final ComputeService computeService = jcloudsCloud.getCompute();

            // Rename that running node with job name and user name
            final String newNodeName = assignNewNodeName(build);
            LOGGER.info("Rename running node(" + nodeId + ") with name: " + newNodeName);
            try {
                computeService.renameNode(nodeId, newNodeName);
            } catch (Exception e) {
                LOGGER.warning("Failed to rename the node to: " + newNodeName + "\n" + e);
            }

            return new Environment() {
                @Override
                public void buildEnvVars(Map<String, String> env) {
                    env.put("JENKINS_NODE_NAME", newNodeName);
                }

                @Override
                public boolean tearDown(AbstractBuild build, final BuildListener listener) throws IOException, InterruptedException {
                    // single-use slave, set to offline to prevent from reusing
                    c.setTemporarilyOffline(true, OfflineCause.create(Messages._OneOffCause()));

                    String slavePostAction = (String) build.getEnvVars().get("slavePostAction");
                    Result buildResult = build.getResult();
                    if (!Strings.isNullOrEmpty(slavePostAction)) {
                        switch (slavePostAction) {
                        case InstancePostAction.OFFLINE_SLAVE:
                            offlineSlave(jcloudsSlave, nodeId, newNodeName);
                            break;
                        case InstancePostAction.OFFLINE_SLAVE_JOB_FAILED:
                            if (buildResult == Result.UNSTABLE || buildResult == Result.FAILURE) {
                                offlineSlave(jcloudsSlave, nodeId, newNodeName);
                            }
                            break;
                        case InstancePostAction.SUSPEND_SLAVE_JOB_FAILED:
                            if (buildResult == Result.UNSTABLE || buildResult != Result.FAILURE) {
                                LOGGER.info("Suspend slave " + jcloudsSlave.getDisplayName() + "(" + nodeId + ") when job failed");
                                jcloudsSlave.setOverrideRetentionTime(-1);
                                //computeService.suspendNode(nodeId);
                            }
                            break;
                        case InstancePostAction.SUSPEND_SLAVE_JOB_DONE:
                            LOGGER.info("Suspend slave " + jcloudsSlave.getDisplayName() + "(" + nodeId + ") when job done");
                            jcloudsSlave.setOverrideRetentionTime(-1);
                            //computeService.suspendNode(nodeId);
                            break;
                        default:
                            // Default to destroy the node, nothing to do and just log it and
                            // let the node being cleaned by cleanup thread
                            LOGGER.info("To delete slave " + jcloudsSlave.getDisplayName() + "(" + nodeId + ")");
                        }
                    }
                    return true;
                }
            };
        } else {
            return new Environment() {
            };
        }
    }

    private void offlineSlave(JCloudsSlave jcloudsSlave, String nodeId, String nodeName) throws IOException {
        LOGGER.info("Offline parameter set: Offline slave " + jcloudsSlave.getDisplayName()
                + "(" + nodeId + ")");
        // default retention: two days (2 * 1440 mins)
        jcloudsSlave.setTerminatedMillTime(System.currentTimeMillis() + 2 * JCloudsConstant.MILLISEC_IN_DAY);
        jcloudsSlave.setLabelString(JCloudsConstant.OFFLINE_LABEL);

        Field nodeDescription = ReflectionUtils.findField(jcloudsSlave.getClass(), "description");
        if (nodeDescription != null) {
            nodeDescription.setAccessible(true);
            try {
                nodeDescription.set(jcloudsSlave, nodeName);
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        JCloudsUtility.updateComputerList();
        JCloudsUtility.saveSettingToConfig();
    }

    private String assignNewNodeName(AbstractBuild build) {
        final String offlineSuffix = "-offline";
        String buildTag = (String) build.getEnvVars().get("BUILD_TAG");
        String nodeName;
        String buildUser = (String) build.getEnvVars().get("BUILD_USER");
        if (Strings.isNullOrEmpty(buildUser)) {
            nodeName = buildTag.replaceFirst("jenkins-","").toLowerCase();
        } else {
            nodeName = (buildTag.replaceFirst("jenkins-","") + "-" + buildUser).toLowerCase();
        }
        String slavePostAction = (String) build.getEnvVars().get("slavePostAction");
        if (InstancePostAction.OFFLINE_SLAVE.equals(slavePostAction)) {
            nodeName = nodeName + offlineSuffix;
        }
        return nodeName;
    }

    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {
        @Override
        public String getDisplayName() {
            return "JClouds Single Slave Plugin-Ex";
        }

        @Override
        public boolean isApplicable(AbstractProject item) {
            return true;
        }

    }
}
