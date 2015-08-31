/*
 * The MIT License
 *
 * Copyright (c) 2004-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi, Tom Huybrechts
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.plugins.jclouds.compute;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import hudson.Extension;
import hudson.model.ManagementLink;
import hudson.model.Node;
import jenkins.model.Jenkins;

/**
 * Add the new label link to manage the cloud offline label.
 * @author Kasper Deng
 */
@Extension
public class JCloudsLabel extends ManagementLink {
    private static final Logger LOGGER = Logger.getLogger(JCloudsLabel.class.getName());

    protected final String name = "OfflineOSInstance";

    public Set<Node> getNodes() {
        Set<Node> nodeSet = new HashSet<>();
        List<Node> nodes = Jenkins.getInstance().getNodes();
        for (Node node : nodes) {
            LOGGER.finest("LABEL: name/description: " + node.getDisplayName() + " " + node.getNodeDescription());
            if (name.equals(node.getLabelString())) {
                nodeSet.add(node);
            }
        }
        return nodeSet;
    }

    public String getName() {
        return name;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "JClouds Offline Instances";
    }

    @Override
    public String getUrlName() {
        return "jclouds-offline";
    }
}
