package jenkins.plugins.jclouds.compute;

import java.util.Date;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

import hudson.model.Descriptor;
import hudson.model.User;
import hudson.model.UserProperty;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import hudson.tasks.Mailer;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;

/**
 * @author Vijay Kiran
 */
public class JCloudsRetentionStrategy extends RetentionStrategy<JCloudsComputer> {
    private transient ReentrantLock checkLock;

    @DataBoundConstructor
    public JCloudsRetentionStrategy() {
        readResolve();
    }

    @Override
    public long check(JCloudsComputer c) {
        if (!checkLock.tryLock()) {
            return 1;
        } else {
            try {
                final JCloudsSlave jCloudsSlave = c.getNode();
                if (c.isIdle() && !jCloudsSlave.isPendingDelete() && !disabled) {
                    // Get the retention time, in minutes, from the JCloudsCloud this JCloudsComputer belongs to.
                    final int retentionTime = c.getRetentionTime();
                    // check executor to ensure we are terminating online slaves
                    if (retentionTime > -1 && c.countExecutors() > 0) {
                        //final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
                        //if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(retentionTime)) {
                        if (c.getRemainRetentionTime() == 0) {
                            LOGGER.info("Setting " + c.getName() + " to be deleted.");
                            if (!c.isOffline()) {
                                c.setTemporarilyOffline(true, OfflineCause.create(Messages._DeletedCause()));
                            }
                            jCloudsSlave.setPendingDelete(true);
                        }
                    }
                }
                if ((c.isIdle()) && (jCloudsSlave.isOfflineOsInstance())
                        && (jCloudsSlave.getRemainRetentionTime() < TimeUnit2.MINUTES.toMillis(240))
                        && (!jCloudsSlave.isEmailNotified())) {
                    // email notification to offline instance owner
                    String nodeDescription = jCloudsSlave.getNodeDescription();
                    String emailAddress = null;
                    User instanceOwner = Jenkins.getInstance().getUser(getUserNameFromNodeDescription(nodeDescription));
                    if (instanceOwner != null) {
                        for (UserProperty up : instanceOwner.getAllProperties()) {
                            if (up instanceof Mailer.UserProperty) {
                                emailAddress = ((Mailer.UserProperty) up).getAddress();
                                break;
                            }
                        }
                    }
                    if (emailAddress != null) {
                        String emailSubject = "Your Offline Instance Will be Terminated in 4 Hours";
                        StringBuilder emailContent = new StringBuilder();
                        String offlineInstance = jCloudsSlave.getNodeName() + " : " + jCloudsSlave.getNodeDescription();
                        emailContent.append("The Offline Instance is: " + offlineInstance);
                        emailContent.append("\r\n");
                        emailContent.append("Terminated Time: " + new Date(jCloudsSlave.getTerminatedMillTime()) + "\r\n");
                        emailContent.append("\r\n You can extend the termination time from here:");
                        emailContent.append("\r\n");
                        emailContent.append(Jenkins.getInstance().getRootUrl() + "computer/" + jCloudsSlave.getNodeName() + "/configure");
                        emailContent.append("\r\n");
                        JCloudsUtility.sendEmail(emailAddress, emailSubject, emailContent);
                        jCloudsSlave.setIsEmailNotified(true);
                    }
                }
            } finally {
                checkLock.unlock();
            }
        }
        return 1;
    }

    /**
     * Try to connect to it ASAP.
     */
    @Override
    public void start(JCloudsComputer c) {
        c.connect(false);
    }

    private String getUserNameFromNodeDescription(String nodeDescription) {
        if (nodeDescription.endsWith("-offline")) {
            String temp = nodeDescription.replace("-offline", "");
            int lastHyphen = temp.lastIndexOf("-");
            return temp.substring(lastHyphen + 1);
        }
        return null;
    }

    // no registration since this retention strategy is used only for cloud nodes that we provision automatically.
    // @Extension
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
        public String getDisplayName() {
            return "JClouds";
        }
    }

    protected Object readResolve() {
        checkLock = new ReentrantLock(false);
        return this;
    }

    private static final Logger LOGGER = Logger.getLogger(JCloudsRetentionStrategy.class.getName());

    public static boolean disabled = Boolean.getBoolean(JCloudsRetentionStrategy.class.getName() + ".disabled");

}
