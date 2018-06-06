package jenkins.plugins.jclouds.compute;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.logging.Level;

import org.apache.commons.io.FileUtils;

import shaded.com.google.common.collect.ImmutableList;
import shaded.com.google.common.util.concurrent.FutureCallback;
import shaded.com.google.common.util.concurrent.Futures;
import shaded.com.google.common.util.concurrent.ListenableFuture;
import shaded.com.google.common.util.concurrent.ListeningExecutorService;
import shaded.com.google.common.util.concurrent.MoreExecutors;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;

@Extension
public final class JCloudsCleanupThread extends AsyncPeriodicWork {

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
    protected void execute(TaskListener listener) {
        final ImmutableList.Builder<ListenableFuture<?>> deletedNodesBuilder = ImmutableList.<ListenableFuture<?>>builder();
        ListeningExecutorService executor = MoreExecutors.listeningDecorator(Computer.threadPoolForRemoting);
        final ImmutableList.Builder<JCloudsComputer> computersToDeleteBuilder = ImmutableList.<JCloudsComputer>builder();

        for (final Computer c : Jenkins.getInstance().getComputers()) {
            if (JCloudsComputer.class.isInstance(c)) {
                final JCloudsComputer comp = (JCloudsComputer) c;
                final JCloudsSlave jCloudsSlave = comp.getNode();
                if (jCloudsSlave != null) {
                    // Ensure the node is still there
                    if (jCloudsSlave.isPendingDelete()) {
                        computersToDeleteBuilder.add(comp);
                        ListenableFuture<?> f = executor.submit(new Callable<String>() {
                            public String call() {
                                logger.log(Level.INFO, "Deleting pending node " + jCloudsSlave.getNodeName());
                                try {
                                    String nodeName = jCloudsSlave.getNodeName();
                                    jCloudsSlave.terminate();
                                    return nodeName;
                                } catch (IOException e) {
                                    logger.log(Level.WARNING, "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
                                } catch (InterruptedException e) {
                                    logger.log(Level.WARNING, "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
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
                    } else if ((c.getChannel() == null) && (jCloudsSlave.isOfflineOsInstance()) &&
                            (jCloudsSlave.isTimeUp())) {
                        logger.log(Level.SEVERE, "Null connection channel in orphan offline node, terminate " +
                                jCloudsSlave.getNodeName() + ", termination time: " +
                                new Date(jCloudsSlave.getTerminatedMillTime()) +  ":"
                                + jCloudsSlave.getNodeDescription());
                        try {
                            jCloudsSlave.terminate();
                        } catch (Exception ex) {
                            logger.log(Level.SEVERE, "Exceptions in orphan node termination! " + ex);
                        }
                    }
                }
            }
        }

        Futures.getUnchecked(Futures.successfulAsList(deletedNodesBuilder.build()));

        for (JCloudsComputer c : computersToDeleteBuilder.build()) {
            try {
                c.deleteSlave();
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                logger.log(Level.WARNING, "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
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
