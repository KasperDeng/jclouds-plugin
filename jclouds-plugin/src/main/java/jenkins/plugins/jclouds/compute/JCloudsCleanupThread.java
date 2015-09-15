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
                            logger.log(Level.INFO, "Deleting pending node " + comp.getName());
                            try {
                                comp.getNode().terminate();
                            } catch (IOException e) {
                                logger.log(Level.WARNING, "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
                            } catch (InterruptedException e) {
                                logger.log(Level.WARNING, "Failed to disconnect and delete " + c.getName() + ": " + e.getMessage());
                            }
                        }
                    });
                    deletedNodesBuilder.add(f);
                } else if ((c.getChannel() == null) && ("OfflineOSInstance".equals(c.getNode().getLabelString()))) {
                    logger.log(Level.SEVERE, "Null connection channel in orphan offline node, terminate " +
                            c.getNode().getNodeName() + ":" + c.getNode().getNodeDescription());
                    try {
                        ((JCloudsComputer) c).getNode().terminate();
                    } catch (Exception e1) {
                        logger.log(Level.SEVERE, "Exceptions in orphan node termination!");
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
}
