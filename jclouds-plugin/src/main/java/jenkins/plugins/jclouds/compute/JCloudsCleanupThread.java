package jenkins.plugins.jclouds.compute;

import java.io.IOException;
import java.util.logging.Level;

import shaded.com.google.common.collect.ImmutableList;
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
                if (((JCloudsComputer) c).getNode().isPendingDelete()) {
                    final JCloudsComputer comp = (JCloudsComputer) c;
                    computersToDeleteBuilder.add(comp);
                    ListenableFuture<?> f = executor.submit(new Runnable() {
                        public void run() {
                            try {
                                if (comp.getRetentionTime() != -1) {
                                    logger.log(Level.INFO, "Deleting pending node " + comp.getName());
                                    comp.getNode().terminate();
                                } else {
                                    // RetentionTime equals -1 means the slave is offline
                                    // only remove it from jenkins and keep instance in openstack for further use
                                    logger.log(Level.INFO, "Remove offline node " + comp.getName());
                                    Jenkins.getInstance().removeNode(comp.getNode());
                                }
                            } catch (IOException e) {
                                logger.log(Level.WARNING, "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
                            } catch (InterruptedException e) {
                                logger.log(Level.WARNING, "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
                            }
                        }
                    });
                    deletedNodesBuilder.add(f);
                }
            }
        }

        Futures.getUnchecked(Futures.successfulAsList(deletedNodesBuilder.build()));

        for (JCloudsComputer c : computersToDeleteBuilder.build()) {
            try {
                // RetentionTime equals -1 means the slave is offline
                if ((c.getNode() != null) && (c.getRetentionTime() != -1)) {
                    c.deleteSlave();
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                logger.log(Level.WARNING, "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
            }

        }
    }
}
