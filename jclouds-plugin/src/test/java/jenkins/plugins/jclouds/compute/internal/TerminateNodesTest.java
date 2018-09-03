package jenkins.plugins.jclouds.compute.internal;

import java.util.List;

<<<<<<< HEAD
=======
import org.junit.Test;
import org.junit.Rule;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.util.concurrent.ExecutionException;

>>>>>>> 7301897bfa748ac9d0d056f4e129968b0c634ec4
import org.jclouds.ContextBuilder;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.logging.Logger;

<<<<<<< HEAD
import jenkins.plugins.jclouds.compute.InstancePostAction;
import junit.framework.TestCase;
import shaded.com.google.common.base.Function;
import shaded.com.google.common.base.Functions;
import shaded.com.google.common.cache.CacheBuilder;
import shaded.com.google.common.cache.CacheLoader;
import shaded.com.google.common.cache.LoadingCache;
import shaded.com.google.common.collect.ImmutableList;
import shaded.com.google.common.collect.ImmutableMap;
import shaded.com.google.common.collect.Iterables;
import shaded.com.google.common.collect.Lists;

public class TerminateNodesTest extends TestCase {

    private ComputeService compute;

    @Override
    protected void setUp() throws Exception {
        compute = ContextBuilder.newBuilder("stub").buildView(ComputeServiceContext.class).getComputeService();
=======
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.jvnet.hudson.test.JenkinsRule;

public class TerminateNodesTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    private static ComputeService compute;

    @BeforeClass
    public static void setUp() {
        Boolean heavy = Boolean.getBoolean("heavyLoadTests");
        assumeTrue(heavy.booleanValue());
        try {
            compute = ContextBuilder.newBuilder("stub").buildView(ComputeServiceContext.class).getComputeService();
        } catch (NoClassDefFoundError e) {
            // Thrown on jenkins.ci.cloudbees.com (probably due to a classloader isolation prob with guice-assistedinject)
        }
>>>>>>> 7301897bfa748ac9d0d056f4e129968b0c634ec4
    }

    @Test
    public void testSuspendOnlySuspendsNodesInQuestion() throws InterruptedException, ExecutionException, RunNodesException {

        if (null != compute) {
            List<NodeMetadata> nodes = ImmutableList.copyOf(compute.createNodesInGroup("suspend", 10));
            List<List<NodeMetadata>> split = Lists.partition(nodes, 5);

            Iterable<RunningNode> runningNodesToSuspend = Iterables.transform(split.get(0), new Function<NodeMetadata, RunningNode>() {

<<<<<<< HEAD
            public RunningNode apply(NodeMetadata input) {
                return new RunningNode("stub", "template", true, InstancePostAction.SUSPEND_SLAVE_JOB_DONE, input);
            }
=======
                public RunningNode apply(NodeMetadata input) {
                    return new RunningNode("stub", "template", true, input);
                }
>>>>>>> 7301897bfa748ac9d0d056f4e129968b0c634ec4

            });

            newTerminateNodes(compute).apply(runningNodesToSuspend);

            for (NodeMetadata node : split.get(0))
                assertEquals(NodeMetadata.Status.SUSPENDED, compute.getNodeMetadata(node.getId()).getStatus());
            for (NodeMetadata node : split.get(1))
                assertEquals(NodeMetadata.Status.RUNNING, compute.getNodeMetadata(node.getId()).getStatus());
        }

    }

    private TerminateNodes newTerminateNodes(ComputeService compute) {
        LoadingCache<String, ComputeService> cache = CacheBuilder.newBuilder().build(
                CacheLoader.<String, ComputeService>from(Functions.forMap(ImmutableMap.of("stub", compute))));
        return new TerminateNodes(Logger.NULL, cache);
    }

    @Test
    public void testDestroyOnlyDestroysNodesInQuestion() throws InterruptedException, ExecutionException, RunNodesException {

        if (null != compute) {
            List<NodeMetadata> nodes = ImmutableList.copyOf(compute.createNodesInGroup("destroy", 10));
            List<List<NodeMetadata>> split = Lists.partition(nodes, 5);

            Iterable<RunningNode> runningNodesToDestroy = Iterables.transform(split.get(0), new Function<NodeMetadata, RunningNode>() {

<<<<<<< HEAD
            public RunningNode apply(NodeMetadata input) {
                return new RunningNode("stub", "template", false, InstancePostAction.DESTROY_SLAVE,input);
            }
=======
                public RunningNode apply(NodeMetadata input) {
                    return new RunningNode("stub", "template", false, input);
                }
>>>>>>> 7301897bfa748ac9d0d056f4e129968b0c634ec4

            });

            newTerminateNodes(compute).apply(runningNodesToDestroy);

            for (NodeMetadata node : split.get(0))
                assertEquals(null, compute.getNodeMetadata(node.getId()));
            for (NodeMetadata node : split.get(1))
                assertEquals(NodeMetadata.Status.RUNNING, compute.getNodeMetadata(node.getId()).getStatus());
        }

    }

    @Test
    public void testSuspendAndDestroy() throws InterruptedException, ExecutionException, RunNodesException {

        if (null != compute) {
            List<NodeMetadata> nodes = ImmutableList.copyOf(compute.createNodesInGroup("suspenddestroy", 10));
            List<List<NodeMetadata>> split = Lists.partition(nodes, 5);

            Iterable<RunningNode> runningNodesToSuspend = Iterables.transform(split.get(0), new Function<NodeMetadata, RunningNode>() {

<<<<<<< HEAD
            public RunningNode apply(NodeMetadata input) {
                return new RunningNode("stub", "template", true, InstancePostAction.SUSPEND_SLAVE_JOB_DONE, input);
            }
=======
                public RunningNode apply(NodeMetadata input) {
                    return new RunningNode("stub", "template", true, input);
                }
>>>>>>> 7301897bfa748ac9d0d056f4e129968b0c634ec4

            });

            Iterable<RunningNode> runningNodesToDestroy = Iterables.transform(split.get(1), new Function<NodeMetadata, RunningNode>() {

<<<<<<< HEAD
            public RunningNode apply(NodeMetadata input) {
                return new RunningNode("stub", "template", false, InstancePostAction.DESTROY_SLAVE, input);
            }
=======
                public RunningNode apply(NodeMetadata input) {
                    return new RunningNode("stub", "template", false, input);
                }
>>>>>>> 7301897bfa748ac9d0d056f4e129968b0c634ec4

            });

            newTerminateNodes(compute).apply(Iterables.concat(runningNodesToSuspend, runningNodesToDestroy));

            for (NodeMetadata node : split.get(0))
                assertEquals(NodeMetadata.Status.SUSPENDED, compute.getNodeMetadata(node.getId()).getStatus());
            for (NodeMetadata node : split.get(1))
                assertEquals(null, compute.getNodeMetadata(node.getId()));
        }

    }

    @AfterClass
    public static void tearDown() throws Exception {
        if (null != compute) {
            compute.getContext().close();
        }
    }
}
