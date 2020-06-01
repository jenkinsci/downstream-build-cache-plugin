package com.axis.system.jenkins.plugins.downstream.cache.pipeline;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** Test downstream pipeline step */
public class DownstreamBuildsStepTest {
  @Rule public JenkinsRule j = new JenkinsRule();

  @Test
  public void noDownstreamBuilds() throws Exception {
    WorkflowJob parent = j.createProject(WorkflowJob.class);
    parent.setDefinition(new CpsFlowDefinition("assert downstreamBuilds() == []", true));
    j.buildAndAssertSuccess(parent);
  }

  @Test
  public void ongoingDownstreams() throws Exception {
    WorkflowJob child1 = j.createProject(WorkflowJob.class, "child1");
    child1.setDefinition(
        new CpsFlowDefinition(
            "org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep.success('waitForChild1/1', null);"
                + "semaphore 'waitForParent'",
            false));
    WorkflowJob child2 = j.createProject(WorkflowJob.class, "child2");
    child2.setDefinition(
        new CpsFlowDefinition(
            "org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep.success('waitForChild2/1', null);"
                + "semaphore 'waitForParent'",
            false));

    WorkflowJob parent = j.createProject(WorkflowJob.class);
    parent.setDefinition(
        new CpsFlowDefinition(
            "build job:'child1', wait:false;"
                + "build job:'child2', wait:false;"
                + "semaphore 'waitForChild1';"
                + "semaphore 'waitForChild2';"
                + "def downstreams = downstreamBuilds().collect {it.projectName};"
                + "assert downstreams == ['child1','child2']",
            true));

    j.buildAndAssertSuccess(parent);
    SemaphoreStep.success("waitForParent/1", null);
    SemaphoreStep.success("waitForParent/2", null);
  }

  @Test
  public void withGrandchild() throws Exception {
    WorkflowJob grandchild = j.createProject(WorkflowJob.class, "grandchild");
    grandchild.setDefinition(new CpsFlowDefinition("", true));

    WorkflowJob child = j.createProject(WorkflowJob.class, "child");
    child.setDefinition(new CpsFlowDefinition("build 'grandchild'", true));

    WorkflowJob parent = j.createProject(WorkflowJob.class);
    parent.setDefinition(
        new CpsFlowDefinition(
            "def childBuild = build 'child';"
                + "assert downstreamBuilds(run:childBuild).collect {it.projectName} == ['grandchild']",
            true));

    j.buildAndAssertSuccess(parent);
  }
}
