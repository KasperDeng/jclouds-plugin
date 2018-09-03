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

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;

@Extension
public final class JCloudsCleanupThread extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(JCloudsCleanupThread.class.getName());

    public JCloudsCleanupThread() {
        super("JClouds slave cleanup");
    }

    @Override
    public long getRecurrencePeriod() {
        return MIN;
    }

    public static void invoke() {
        getInstance().run();
    }

    private static JCloudsCleanupThread getInstance() {
        return Jenkins.getInstance().getExtensionList(AsyncPeriodicWork.class).get(JCloudsCleanupThread.class);
    }

    @Override
    protected Level getNormalLoggingLevel() {
        return Level.FINE;
    }

    @Override
    protected void execute(TaskListener listener) {
        final ImmutableList.Builder<ListenableFuture<?>> deletedNodesBuilder = ImmutableList.<ListenableFuture<?>>builder();
        ListeningExecutorService executor = MoreExecutors.listeningDecorator(Computer.threadPoolForRemoting);
        final ImmutableList.Builder<JCloudsComputer> computersToDeleteBuilder = ImmutableList.<JCloudsComputer>builder();

        for (final Computer c : Jenkins.getInstance().getComputers()) {
            if (JCloudsComputer.class.isInstance(c)) {
                final JCloudsComputer comp = (JCloudsComputer) c;
                final JCloudsSlave node = comp.getNode();
                // Ensure the node is still there
                if (node.isPendingDelete()) {
                    computersToDeleteBuilder.add(comp);
                    ListenableFuture<?> f = executor.submit(new Callable<String>() {
                        @Override
                        public String call() {
                            LOGGER.log(Level.INFO, "Deleting pending node " + node.getNodeName());
                            try {
                                String nodeName = node.getNodeName();
                                node.terminate();
                                return nodeName;
                            } catch (IOException e) {
                                LOGGER.log(Level.WARNING,
                                        "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
                            } catch (InterruptedException e) {
                                LOGGER.log(Level.WARNING,
                                        "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
                            }
                            return null;
                        }
                    });
                    Futures.addCallback(f, new FutureCallback<Object>() {
                        @Override
                        public void onSuccess(Object nodeName) {
                            deleteSlaveLog((String) nodeName);
                        }

                        @Override
                        public void onFailure(Throwable throwable) {

                        }
                    });
                    deletedNodesBuilder.add(f);
                } else if ((c.getChannel() == null) && (node.isOfflineOsInstance()) && (node.isTimeUp())) {
                    LOGGER.log(Level.SEVERE,
                            "Null connection channel in orphan offline node, terminate " + node.getNodeName()
                                    + ", termination time: " + new Date(node.getTerminatedMillTime()) + ":"
                                    + node.getNodeDescription());
                    try {
                        node.terminate();
                    } catch (Exception ex) {
                        LOGGER.log(Level.SEVERE, "Exceptions in orphan node termination! " + ex);
                    }
                }
            }
        }

        Futures.getUnchecked(Futures.successfulAsList(deletedNodesBuilder.build()));

        for (JCloudsComputer c : computersToDeleteBuilder.build()) {
            try {
                c.deleteSlave();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                LOGGER.log(Level.WARNING, "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
            }

        }
    }

    /**
     * Delete the slave log, especially when slave terminated.
     * For saving disk space.
     */
    private void deleteSlaveLog(String nodeName) {
        String logPath = Jenkins.getInstance().getRootDir() + "/logs/slaves/" + nodeName;
        FileUtils.deleteQuietly(new File(logPath));
    }
}
