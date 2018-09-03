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
