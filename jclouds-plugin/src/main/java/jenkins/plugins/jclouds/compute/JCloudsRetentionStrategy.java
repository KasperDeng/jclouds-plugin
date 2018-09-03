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

    private void fastTerminate(final JCloudsComputer c) {
        if (!c.isOffline()) {
            LOGGER.info("Setting " + c.getName() + " to be deleted.");
            try {
                c.disconnect(OfflineCause.create(Messages._DeletedCause())).get();
            } catch (Exception e) {
                LOGGER.info("Caught " + e.toString());
            }
        }
        c.deleteSlave(true);
    }

    @Override
    public long check(JCloudsComputer c) {
        if (!checkLock.tryLock()) {
            return 1;
        } else {
            try {
                final JCloudsSlave node = c.getNode();
                // check isIdle() to ensure we are terminating busy slaves (including FlyWeight)
                if (null != node && c.isIdle()) {
                    if (node.isPendingDelete()) {
                        // Fixes JENKINS-28403
                        fastTerminate(c);
                    } else if (!node.isWaitPhoneHome()) {
                        // Get the retention time, in minutes, from the JCloudsCloud this JCloudsComputer belongs to.
                        final int retentionTime = c.getRetentionTime();
                        if (retentionTime > -1 && c.countExecutors() > 0) {
                            //                            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
                            //                            LOGGER.fine("Node " + c.getName() + " retentionTime: " + retentionTime + " idle: "
                            //                                    + TimeUnit2.MILLISECONDS.toMinutes(idleMilliseconds) + "min");
                            //                            if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(retentionTime)) {
                            if (c.getRemainRetentionTime() == 0) {
                                LOGGER.info("Retention time for " + c.getName() + " has expired.");
                                node.setPendingDelete(true);
                                fastTerminate(c);
                            }
                        }
                    }
                }
                if ((c.isIdle()) && (node.isOfflineOsInstance())
                        && (node.getRemainRetentionTime() < TimeUnit2.MINUTES.toMillis(240))
                        && (!node.isEmailNotified())) {
                    // email notification to offline instance owner
                    String nodeDescription = node.getNodeDescription();
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
                        String offlineInstance = node.getNodeName() + " : " + node.getNodeDescription();
                        emailContent.append("The Offline Instance is: " + offlineInstance);
                        emailContent.append("\r\n");
                        emailContent.append("Terminated Time: " + new Date(node.getTerminatedMillTime()) + "\r\n");
                        emailContent.append("\r\n You can extend the termination time from here:");
                        emailContent.append("\r\n");
                        emailContent.append(
                                Jenkins.getInstance().getRootUrl() + "computer/" + node.getNodeName() + "/configure");
                        emailContent.append("\r\n");
                        JCloudsUtility.sendEmail(emailAddress, emailSubject, emailContent);
                        node.setIsEmailNotified(true);
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

    // Serialization
    protected Object readResolve() {
        checkLock = new ReentrantLock(false);
        return this;
    }

    private static final Logger LOGGER = Logger.getLogger(JCloudsRetentionStrategy.class.getName());
}
