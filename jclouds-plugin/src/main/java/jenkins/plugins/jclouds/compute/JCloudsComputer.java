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

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.jclouds.compute.domain.NodeMetadata;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;

import hudson.model.Descriptor;
import hudson.remoting.VirtualChannel;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;

/**
 * JClouds version of Jenkins {@link SlaveComputer} - responsible for terminating an instance.
 *
 * @author Vijay Kiran
 */
public class JCloudsComputer extends AbstractCloudComputer<JCloudsSlave> implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(JCloudsComputer.class.getName());

    private final ProvisioningActivity.Id provisioningId;

    public JCloudsComputer(JCloudsSlave slave) {
        super(slave);
        this.provisioningId = slave.getId();
    }

    public String getInstanceId() {
        return getNode().getNodeId();
    }


    public int getRetentionTime() {
        final JCloudsSlave node = getNode();
        return null == node ? CloudInstanceDefaults.DEFAULT_INSTANCE_RETENTION_TIME_IN_MINUTES
                : node.getRetentionTime();
    }

    @CheckForNull
    public String getCloudName() {
        final JCloudsSlave node = getNode();
        return null == node ? null : node.getCloudName();
    }

    @Override
    public String getName() {
        JCloudsSlave jCloudsSlave = getNode();
        return jCloudsSlave != null ? jCloudsSlave.getNodeName() : "";
    }

    public long getRemainRetentionTime() {
        return getNode().getRemainRetentionTime();
    }

    /**
     * Deletes a jenkins slave node. The not is first marked pending
     * delete and the actual deletion will be performed at the next run of
     * {@link JCloudsCleanupThread}. If called again after already being
     * marked, the deletion is performed immediately.
     */
    @Override
    public HttpResponse doDoDelete() throws IOException {
        if (isNodeOwner() || JCloudsUtility.isAdmin()) {
            disconnect(OfflineCause.create(Messages._DeletedCause()));
            final JCloudsSlave node = getNode();
            if (null != node) {
                if (node.isPendingDelete()) {
                    // User attempts to delete an already delete-pending slave
                    LOGGER.info("Slave already pendig delete: " + getName());
                    deleteSlave(true);
                } else {
                    node.setPendingDelete(true);
                }
            }
            return new HttpRedirect("..");
        }
        throw new IOException("You don't have privilege to delete the offline slave " + getNode().getNodeName() +
                ":" + getNode().getNodeDescription() + " not of you!");
    }

    @Override
    public void doConfigSubmit(StaplerRequest req, StaplerResponse rsp)
            throws IOException, ServletException, Descriptor.FormException {
        String endDate = req.getParameter("endDate");//endDate 2015-08-28 03:15
        if ("Forever".equalsIgnoreCase(endDate) || "-1".equalsIgnoreCase(endDate)) {
            getNode().setOverrideRetentionTime(-1);
        } else {
            DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            try {
                Date newTerminatedDate = df.parse(endDate + ":00");
                getNode().setTerminatedMillTime(newTerminatedDate.getTime());
                Long extendTime = newTerminatedDate.getTime() - System.currentTimeMillis();
                LOGGER.info("Debug date.getTime(): " + newTerminatedDate.getTime() + " extendTime: " + extendTime);
            } catch (ParseException e) {
                LOGGER.info("The input terminated date is wrong format!");
            }
        }

        // take the user back to the slave top page.
        rsp.sendRedirect2("../" + getName() + '/');
    }

    /**
     * Delete the slave, terminate or suspend the instance. Can be called
     * either by doDoDelete() or from JCloudsRetentionStrategy. Whether
     * the instance gets terminated or suspended is handled in
     * {@link JCloudsSlave#_terminate}
     *
     * @throws InterruptedException if the deletion gets interrupted.
     * @throws IOException if an error occurs.
     */
    public void deleteSlave() throws IOException, InterruptedException {
        if (isIdle()) { // Fixes JENKINS-27471
            LOGGER.info("Deleting slave: " + getName());
            JCloudsSlave slave = getNode();
            if (null != slave) {
                LOGGER.info("Terminating " + getName() + " slave");
                final VirtualChannel ch = slave.getChannel();
                if (null != ch) {
                    ch.close();
                }
                slave.terminate();
                Jenkins.getInstance().removeNode(slave);
            }
        } else {
            LOGGER.info(String.format("Slave %s is not idle, postponing deletion", getName()));
            // Fixes JENKINS-28403
            final JCloudsSlave node = getNode();
            if (null != node && !node.isPendingDelete()) {
                node.setPendingDelete(true);
            }
        }
    }

    /**
     * Delete the slave, terminate or suspend the instance. Like
     * {@link #deleteSlave}, but catching all exceptions and logging the
     * if desired.
     *
     * @param logging {@code true}, if exception logging is desired.
     */
    public void deleteSlave(final boolean logging) {
        try {
            deleteSlave();
        } catch (Exception e) {
            if (logging) {
                LOGGER.log(Level.WARNING, "Failed to delete slave", e);
            }
        }
    }

    private Set<String> getIpAddresses(final boolean wantPublic) {
        final JCloudsSlave node = getNode();
        if (null != node) {
            final NodeMetadata md = node.getNodeMetaData();
            Set<String> ret = wantPublic ? md.getPublicAddresses() : md.getPrivateAddresses();
            if (!ret.isEmpty()) {
                return ret;
            }
        }
        return ImmutableSet.<String> of("None");
    }

    private String MarkPreferredAddress(final String txt, final String pre, final String post) {
        final JCloudsSlave node = getNode();
        if (null != node) {
            final NodeMetadata md = node.getNodeMetaData();
            final String match = JCloudsLauncher.getConnectionAddress(md, null, node.getPreferredAddress());
            return txt.replace(match, pre + match + post);
        }
        return txt;
    }

    /**
     * To check the node whether belongs to current user
     */
    private Boolean isNodeOwner() {
        String nodeDesc = getNode().getNodeDescription();
        String userName = JCloudsUtility.getCurrentUserName();
        return StringUtils.containsIgnoreCase(nodeDesc, userName);
    }

    public String getPublicIpAddressHeader() {
        return "Public IP-Address" + (getIpAddresses(true).size() > 1 ? "es" : "");
    }

    public String getPrivateIpAddressHeader() {
        return "Private IP-Address" + (getIpAddresses(false).size() > 1 ? "es" : "");
    }

    public String getPublicIpAddresses() {
        return MarkPreferredAddress(Joiner.on(" ").join(getIpAddresses(true)), "<b>", "</b>");
    }

    public String getPrivateIpAddresses() {
        return MarkPreferredAddress(Joiner.on(" ").join(getIpAddresses(false)), "<b>", "</b>");
    }

    @Nullable
    @Override
    public ProvisioningActivity.Id getId() {
        return provisioningId;
    }
}
