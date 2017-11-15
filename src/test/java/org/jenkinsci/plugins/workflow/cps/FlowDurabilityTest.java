package org.jenkinsci.plugins.workflow.cps;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Result;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.NodeStepNamePredicate;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.support.storage.FlowNodeStorage;
import org.jenkinsci.plugins.workflow.support.storage.LumpFlowNodeStorage;
import org.jenkinsci.plugins.workflow.support.storage.SimpleXStreamFlowNodeStorage;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests implementations designed to verify handling of the flow durability levels and persistence of pipeline state.
 *
 * <strong>This means:</strong>
 * <ol>
 *     <li>Persisting FlowNode data in an appropriately consistent fashion.</li>
 *     <li>Persisting Program data for running pipelines as appropriate.</li>
 *     <li>Not leaving orphaned durable tasks.</li>
 *     <li>Being able to load runs & FlowExecutions as appropriate, with up-to-date info.</li>
 *     <li>Being able to resume execution when we are supposed to.</li>
 * </ol>
 */
public class FlowDurabilityTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();


    static WorkflowRun createAndRunBasicJob(Jenkins jenkins, String jobName, FlowDurabilityHint durabilityHint) throws Exception {
        return createAndRunBasicJob(jenkins, jobName, durabilityHint, 1);
    }

    static void assertBaseStorageType(FlowExecution exec, Class<? extends FlowNodeStorage> storageClass) throws Exception {
        if (exec instanceof CpsFlowExecution) {
            FlowNodeStorage store = ((CpsFlowExecution) exec).getStorage();
            if (store instanceof CpsFlowExecution.TimingFlowNodeStorage) {
                Field f = CpsFlowExecution.TimingFlowNodeStorage.class.getDeclaredField("delegate");
                f.setAccessible(true);
                FlowNodeStorage delegateStore = (FlowNodeStorage)(f.get(store));
                Assert.assertEquals(storageClass.toString(), delegateStore.getClass().toString());
            }
        }
    }

    /** Create and run a job with a semaphore and basic steps -- takes a semaphoreIndex in case you have multiple semaphores of the same name in one test.*/
    static WorkflowRun createAndRunBasicJob(Jenkins jenkins, String jobName, FlowDurabilityHint durabilityHint, int semaphoreIndex) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, jobName);
        CpsFlowDefinition def = new CpsFlowDefinition("node {\n " +
                "semaphore 'halt' \n" +
                "} \n" +
                "echo 'I like chese'\n", false);
        def.setDurabilityHint(durabilityHint);
        job.setDefinition(def);
        WorkflowRun run = job.scheduleBuild2(0).getStartCondition().get();
        SemaphoreStep.waitForStart("halt/"+semaphoreIndex, run);
        Assert.assertEquals(durabilityHint, run.getExecution().getDurabilityHint());
        if (durabilityHint == FlowDurabilityHint.NO_PROMISES || durabilityHint == FlowDurabilityHint.SURVIVE_CLEAN_RESTART) {
            assertBaseStorageType(run.getExecution(), LumpFlowNodeStorage.class);
        } else {
            assertBaseStorageType(run.getExecution(), SimpleXStreamFlowNodeStorage.class);
        }
        return run;
    }

    static WorkflowRun createAndRunSleeperJob(Jenkins jenkins, String jobName, FlowDurabilityHint durabilityHint) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, jobName);
        CpsFlowDefinition def = new CpsFlowDefinition("node {\n " +
                "sleep 30 \n" +
                "} \n" +
                "echo 'I like chese'\n", false);
        def.setDurabilityHint(durabilityHint);
        job.setDefinition(def);
        WorkflowRun run = job.scheduleBuild2(0).getStartCondition().get();
        Thread.sleep(4000L);  // Hacky but we just need to ensure this can start up
        Assert.assertEquals(durabilityHint, run.getExecution().getDurabilityHint());
        return run;
    }

    static void verifySucceededCleanly(Jenkins j, WorkflowRun run) throws Exception {
        Assert.assertEquals(Result.SUCCESS, run.getResult());
        FlowExecution exec = run.getExecution();
        verifyCompletedCleanly(j, run);

        // Confirm the flow graph is fully navigable and contains the heads with appropriate ending
        DepthFirstScanner scan = new DepthFirstScanner();
        List<FlowNode> allNodes = scan.allNodes(exec);
        FlowNode endNode = exec.getCurrentHeads().get(0);
        Assert.assertEquals(FlowEndNode.class, endNode.getClass());
        assert allNodes.contains(endNode);
        Assert.assertEquals(8, allNodes.size());

        // Graph structure assertions
        Assert.assertEquals(2, scan.filteredNodes(endNode, (Predicate)(Predicates.instanceOf(StepStartNode.class))).size());
        Assert.assertEquals(2, scan.filteredNodes(endNode, (Predicate)(Predicates.instanceOf(StepEndNode.class))).size());
        Assert.assertEquals(1, scan.filteredNodes(endNode, (Predicate)(Predicates.instanceOf(FlowStartNode.class))).size());

        // TODO fix the fact that echo step won't match descriptorImpl to descriptorImpl with NodeStepTypePredicate
        // Because instances don't match.
        Predicate<FlowNode> sleepOrSemaphoreMatch = Predicates.or(
                new NodeStepNamePredicate(StepDescriptor.byFunctionName("semaphore").getId()),
                new NodeStepNamePredicate(StepDescriptor.byFunctionName("sleep").getId())
        );
        Assert.assertEquals(1, scan.filteredNodes(endNode, sleepOrSemaphoreMatch).size());
        Assert.assertEquals(1, scan.filteredNodes(endNode, new NodeStepNamePredicate(StepDescriptor.byFunctionName("echo").getId())).size());

        for (FlowNode node : (List<FlowNode>)(scan.filteredNodes(endNode, (Predicate)(Predicates.instanceOf(StepNode.class))))) {
            Assert.assertNotNull("Node: "+node.toString()+" does not have a TimingAction", node.getAction(TimingAction.class));
        }
    }

    /** If it's a {@link SemaphoreStep} we test less rigorously because that blocks async GraphListeners. */
    static void verifySafelyResumed(JenkinsRule rule, WorkflowRun run, boolean isSemaphore) throws Exception {
        assert run.isBuilding();
        FlowExecution exec = run.getExecution();

        // Assert that we have the appropriate flow graph entries
        List<FlowNode> heads = exec.getCurrentHeads();
        Assert.assertEquals(1, heads.size());
        FlowNode node = heads.get(0);
        String name = node.getDisplayFunctionName();
        Assert.assertTrue("Head node not a semaphore step or sleep: "+name, "semaphore".equals(name) || "sleep".equals(name));
        if (!isSemaphore) {
            Assert.assertNotNull(node.getPersistentAction(TimingAction.class));
            Assert.assertNotNull(node.getPersistentAction(ArgumentsAction.class));
            Assert.assertNotNull(node.getAction(LogAction.class));
        } else {
            SemaphoreStep.success("halt/1", Result.SUCCESS);
        }

        rule.waitForCompletion(run);
        verifySucceededCleanly(rule.jenkins, run);
    }

    /** Waits until the build to resume or die. */
    static void waitForBuildToResumeOrFail(CpsFlowExecution execution) throws TimeoutException {
        long nanoStartTime = System.nanoTime();
        while (true) {
            if (execution.isComplete() || (execution.programPromise != null && execution.programPromise.isDone())) {
                return;
            }
            long currentTime = System.nanoTime();
            if (TimeUnit.SECONDS.convert(currentTime-nanoStartTime, TimeUnit.NANOSECONDS) > 5) {
                throw new TimeoutException();
            }
        }
    }

    static void verifyFailedCleanly(Jenkins j, WorkflowRun run) throws Exception {

        if (run.isBuilding()) {  // Give the run a little bit of time to see if it can resume or not
            FlowExecution exec = run.getExecution();
            if (exec instanceof CpsFlowExecution) {
                waitForBuildToResumeOrFail((CpsFlowExecution)exec);
            } else {
                Thread.sleep(2000L);
            }
        }

        assert !run.isBuilding();
        assert run.getResult() == Result.FAILURE || run.getResult() == Result.ABORTED;
        // TODO verify all blocks cleanly closed out, so Block start and end nodes have same counts and FlowEndNode is last node
        verifyCompletedCleanly(j, run);
    }

    /** Verifies all the universal post-build cleanup was done, regardless of pass/fail state. */
    static void verifyCompletedCleanly(Jenkins j, WorkflowRun run) throws Exception {
        // Assert that we have the appropriate flow graph entries
        FlowExecution exec = run.getExecution();
        List<FlowNode> heads = exec.getCurrentHeads();
        Assert.assertEquals(1, heads.size());
        verifyNoTasksRunning(j);
        Assert.assertEquals(0, exec.getCurrentExecutions(false).get().size());

        if (exec instanceof CpsFlowExecution) {
            CpsFlowExecution cpsFlow = (CpsFlowExecution)exec;
            assert cpsFlow.getStorage() != null;
            Assert.assertFalse("Should always be able to retrieve script", StringUtils.isEmpty(cpsFlow.getScript()));
            Assert.assertNull("We should have no Groovy shell left or that's a memory leak", cpsFlow.getShell());
            Assert.assertNull("We should have no Groovy shell left or that's a memory leak", cpsFlow.getTrustedShell());
        }
    }

    /** Verifies we have nothing left that uses an executor for a given job. */
    static void verifyNoTasksRunning(Jenkins j) {
        assert j.getQueue().isEmpty();
        Computer[] computerList = j.getComputers();
        for (Computer c : computerList) {
            List<Executor> executors = c.getExecutors();
            for (Executor ex : executors) {
                if (ex.isBusy()) {
                    Assert.fail("Computer "+c+" has an Executor "+ex+" still running a task: "+ex.getCurrentWorkUnit());
                }
            }
        }
    }

    /**
     * Confirm that for ALL implementations, a run can complete and be loaded if you restart after completion.
     */
    @Test
    public void testCompleteAndLoadBuilds() throws Exception {
        final FlowDurabilityHint[] durabilityHints = FlowDurabilityHint.values();
        final WorkflowJob[] jobs = new WorkflowJob[durabilityHints.length];

        // Create and run jobs for each of the durability hints
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                int i = 1;
                for (FlowDurabilityHint hint : durabilityHints) {
                    try{
                        WorkflowRun run = createAndRunBasicJob(story.j.jenkins, "basicJob-"+hint.toString(), hint, i);
                        jobs[i-1] = run.getParent();
                        SemaphoreStep.success("halt/"+i++,Result.SUCCESS);
                        story.j.waitForCompletion(run);
                        story.j.assertBuildStatus(Result.SUCCESS, run);
                    } catch (AssertionError ae) {
                        System.out.println("Error with durability level: "+hint);
                        throw ae;
                    }
                }
            }
        });

        //Restart and confirm we can still load them.
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                for (int i=0; i<durabilityHints.length; i++) {
                    WorkflowJob j  = jobs[i];
                    try{
                        WorkflowRun run = j.getLastBuild();
                        verifySucceededCleanly(story.j.jenkins, run);
                        Assert.assertEquals(durabilityHints[i], run.getExecution().getDurabilityHint());
                    } catch (AssertionError ae) {
                        System.out.println("Error with durability level: "+j.getDefinition().getDurabilityHint());
                        throw ae;
                    }
                }
            }
        });
    }

    /**
     * Verifies that if we're only durable against clean restarts, the pipeline will survive it.
     */
    @Test
    @Ignore
    public void demonstrateReloadFailureWithSemaphoreStep() throws Exception {
        final String jobName = "durableAgainstClean";

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Jenkins jenkins = story.j.jenkins;
                createAndRunBasicJob(story.j.jenkins, jobName, FlowDurabilityHint.SURVIVE_CLEAN_RESTART);
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName(jobName, WorkflowJob.class).getLastBuild();
                Assert.assertEquals(FlowDurabilityHint.SURVIVE_CLEAN_RESTART, run.getExecution().getDurabilityHint());
                verifySafelyResumed(story.j, run, true);
            }
        });
    }

    /**
     * Verifies that if we're only durable against clean restarts, the pipeline will survive it.
     */
    @Test
    public void testDurableAgainstCleanRestartSurvivesIt() throws Exception {
        final String jobName = "durableAgainstClean";

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Jenkins jenkins = story.j.jenkins;
                WorkflowRun run = createAndRunSleeperJob(story.j.jenkins, jobName, FlowDurabilityHint.SURVIVE_CLEAN_RESTART);
                FlowExecution exec = run.getExecution();
                assertBaseStorageType(exec, LumpFlowNodeStorage.class);
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName(jobName, WorkflowJob.class).getLastBuild();
                Assert.assertEquals(FlowDurabilityHint.SURVIVE_CLEAN_RESTART, run.getExecution().getDurabilityHint());
                assertBaseStorageType(run.getExecution(), LumpFlowNodeStorage.class);
                verifySafelyResumed(story.j, run, true);
            }
        });
    }

    /** Verify that our flag for whether or not a build was cleanly persisted gets reset when things happen.
     */
    @Test
    @Ignore // For now
    public void testDurableAgainstCleanRestartResetsCleanlyPersistedFlag() throws Exception {
        final String jobName = "durableAgainstClean";
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowJob job = story.j.jenkins.createProject(WorkflowJob.class, jobName);
                CpsFlowDefinition def = new CpsFlowDefinition("node {\n " +
                        "  sleep 30 \n" +
                        "  dir('nothing'){sleep 30;}\n"+
                        "} \n" +
                        "echo 'I like chese'\n", false);
                def.setDurabilityHint(FlowDurabilityHint.SURVIVE_CLEAN_RESTART);
                job.setDefinition(def);
                WorkflowRun run = job.scheduleBuild2(0).getStartCondition().get();
                Thread.sleep(2000L);  // Hacky but we just need to ensure this can start up
            }
        });
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName("durableAgainstClean", WorkflowJob.class).getLastBuild();
                Thread.sleep(350000);  // Step completes
                WorkflowJob job = story.j.jenkins.createProject(WorkflowJob.class, jobName);
                CpsFlowExecution exec = (CpsFlowExecution)(job.getLastBuild().getExecution());
                assert exec.persistedClean == null;
            }
        });
    }


    /** Verify that if the master dies messily and we're not durable against that, build fails cleanly.
     */
    @Test
    public void testDurableAgainstCleanRestartFailsWithDirtyShutdown() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = createAndRunSleeperJob(story.j.jenkins, "durableAgainstClean", FlowDurabilityHint.SURVIVE_CLEAN_RESTART);
                // DURABILITY HINT WAS RESET TO FULLY DURABLE SOMEHOW
                Assert.assertEquals(FlowDurabilityHint.SURVIVE_CLEAN_RESTART, run.getExecution().getDurabilityHint());
                simulateAbruptFailure(story);
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName("durableAgainstClean", WorkflowJob.class).getLastBuild();
                verifyFailedCleanly(story.j.jenkins, run);
            }
        });
    }

    /** Verify that if the master dies messily and we're not durable against restarts, the build fails somewhat cleanly.
     */
    @Test
    public void testNotDurableFailsOnDirtyShutdown() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                createAndRunSleeperJob(story.j.jenkins, "nonDurable", FlowDurabilityHint.NO_PROMISES);
                simulateAbruptFailure(story);
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName("nonDurable", WorkflowJob.class).getLastBuild();
                verifyFailedCleanly(story.j.jenkins, run);
            }
        });
    }

    /** Sanity check that fully durable pipelines shutdown and restart cleanly */
    @Test
    public void testFullyDurableSurvivesCleanRestart() throws Exception {
        final String jobName = "survivesEverything";

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Jenkins jenkins = story.j.jenkins;
                WorkflowRun run = createAndRunBasicJob(story.j.jenkins, jobName, FlowDurabilityHint.FULLY_DURABLE);
                FlowExecution exec = run.getExecution();
                if (exec instanceof CpsFlowExecution) {
                    assert ((CpsFlowExecution) exec).getStorage().isPersistedFully();
                }
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName(jobName, WorkflowJob.class).getLastBuild();
                verifySafelyResumed(story.j, run, true);
            }
        });
    }

    /**
     * Sanity check that fully durable pipelines can survive hard kills.
     */
    @Test
    public void testFullyDurableSurvivesDirtyRestart() throws Exception {
        final String jobName = "survivesEverything";

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Jenkins jenkins = story.j.jenkins;
                WorkflowRun run = createAndRunSleeperJob(story.j.jenkins, jobName, FlowDurabilityHint.FULLY_DURABLE);
                FlowExecution exec = run.getExecution();
                if (exec instanceof CpsFlowExecution) {
                    assert ((CpsFlowExecution) exec).getStorage().isPersistedFully();
                }
                simulateAbruptFailure(story);
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName(jobName, WorkflowJob.class).getLastBuild();
                verifySafelyResumed(story.j, run, false);
            }
        });
    }

    /** https://stackoverflow.com/questions/6214703/copy-entire-directory-contents-to-another-directory */
    public static class CopyFileVisitor extends SimpleFileVisitor<Path> {
        private final Path targetPath;
        private Path sourcePath = null;
        public CopyFileVisitor(Path targetPath) {
            this.targetPath = targetPath;
        }

        @Override
        public FileVisitResult preVisitDirectory(final Path dir,
                                                 final BasicFileAttributes attrs) throws IOException {
            if (sourcePath == null) {
                sourcePath = dir;
            } else {
                Files.createDirectories(targetPath.resolve(sourcePath
                        .relativize(dir)));
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(final Path file,
                                         final BasicFileAttributes attrs) throws IOException {
            if (!Files.isSymbolicLink(file)) {
                // Needed because Jenkins includes invalid lastSuccessful symlinks and otherwise we get a NoSuchFileException
                Files.copy(file,
                        targetPath.resolve(sourcePath.relativize(file)));
            } else if (Files.isSymbolicLink(file) && Files.exists(Files.readSymbolicLink(file))) {
                Files.copy(file,
                        targetPath.resolve(sourcePath.relativize(file)));
            }
            return FileVisitResult.CONTINUE;
        }
    }

    /**
     * Simulate an abrupt failure of Jenkins to see if it appropriately handles inconsistent states when
     *  shutdown cleanup is not performed or data is not written fully to disk.
     *
     * Works by copying the JENKINS_HOME to a new directory and then setting the {@link RestartableJenkinsRule} to use
     * that for the next restart. Thus we only have the data actually persisted to disk at that time to work with.
     *
     * Should be run as the last part of a {@link org.jvnet.hudson.test.RestartableJenkinsRule.Step}.
     *
     * @param rule Restartable JenkinsRule to use for simulating failure and restart
     * @throws IOException
     */
    public static void simulateAbruptFailure(RestartableJenkinsRule rule) throws IOException {
        File homeDir = rule.home;
        TemporaryFolder temp = new TemporaryFolder();
        temp.create();
        File newHome = temp.newFolder();

        // Copy efficiently
        Files.walkFileTree(homeDir.toPath(), Collections.EMPTY_SET, 99, new CopyFileVisitor(newHome.toPath()));
        rule.home = newHome;
    }
}