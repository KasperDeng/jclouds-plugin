package jenkins.plugins.jclouds.compute;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;

/**
 * JClouds version of Jenkins {@link SlaveComputer} - responsible for terminating an instance.
 *
 * @author Vijay Kiran
 */
public class JCloudsComputer extends AbstractCloudComputer<JCloudsSlave> {

    private static final Logger LOGGER = Logger.getLogger(JCloudsComputer.class.getName());

    public JCloudsComputer(JCloudsSlave slave) {
        super(slave);
    }

    public String getInstanceId() {
        return getNode().getNodeId();
    }

    @Override
    public JCloudsSlave getNode() {
        return super.getNode();
    }

    public int getRetentionTime() {
        return getNode().getRetentionTime();
    }

    public String getCloudName() {
        return getNode().getCloudName();
    }

    public String getName() {
        return getNode().getNodeName();
    }

    public long getRemainRetentionTime() {
        return getNode().getRemainRetentionTime();
    }

    /**
     * Really deletes the slave, by terminating the instance.
     */
    @Override
    public HttpResponse doDoDelete() throws IOException {
        if (isNodeOwner() || JCloudsUtility.isAdmin()) {
            setTemporarilyOffline(true, OfflineCause.create(Messages._DeletedCause()));
            getNode().setPendingDelete(true);
            return new HttpRedirect("..");
        }
        throw new IOException("You don't have privilege to delete the offline slave " + getNode().getNodeName() +
                ":" + getNode().getNodeDescription() + " not of you!");
    }

    @Override
    public void doConfigSubmit(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException,
            Descriptor.FormException {
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
     * Delete the slave, terminate the instance. Can be called either by doDoDelete() or from JCloudsRetentionStrategy.
     *
     * @throws InterruptedException
     */
    public void deleteSlave() throws IOException, InterruptedException {
        JCloudsSlave slave = getNode();

        // Slave already deleted
        if (slave == null)
            return;

        LOGGER.info("Terminating " + getName() + " slave");

        if (slave.getChannel() != null) {
            slave.getChannel().close();
        }
        slave.terminate();
        Hudson.getInstance().removeNode(slave);
    }

    /**
     * To check the node whether belongs to current user
     */
    private Boolean isNodeOwner() {
        String nodeDesc = getNode().getNodeDescription();
        String userName = JCloudsUtility.getCurrentUserName();
        return StringUtils.containsIgnoreCase(nodeDesc, userName);
    }
}
