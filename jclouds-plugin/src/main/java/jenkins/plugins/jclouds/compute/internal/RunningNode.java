package jenkins.plugins.jclouds.compute.internal;

import org.jclouds.compute.domain.NodeMetadata;

public class RunningNode {
    private final String cloud;
    private final String template;
    private final boolean suspendOrTerminate;
    private final NodeMetadata node;
    private String slavePostAction;

    public RunningNode(String cloud, String template, boolean suspendOrTerminate, String slavePostAction,
            NodeMetadata node) {
        this.cloud = cloud;
        this.template = template;
        this.suspendOrTerminate = suspendOrTerminate;
        this.node = node;
        this.slavePostAction = slavePostAction;
    }

    public String getCloudName() {
        return cloud;
    }

    public String getTemplateName() {
        return template;
    }

    public boolean isSuspendOrTerminate() {
        return suspendOrTerminate;
    }

    public NodeMetadata getNode() {
        return node;
    }

    public String getSlavePostAction() {
        return slavePostAction;
    }

    public void setSlavePostAction(String slavePostAction) {
        this.slavePostAction = slavePostAction;
    }
}
