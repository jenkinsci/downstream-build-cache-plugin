package com.axis.system.jenkins.plugins.downstream.cache.pipeline;

import com.axis.system.jenkins.plugins.downstream.cache.BuildCache;
import hudson.Extension;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import org.jenkinsci.plugins.workflow.steps.*;
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class DownstreamBuildsStep extends Step {

  @DataBoundSetter private RunWrapper run;

  @DataBoundConstructor
  public DownstreamBuildsStep() {}

  @Override
  public StepExecution start(StepContext stepContext) throws Exception {
    if (run == null) {
      run = new RunWrapper(stepContext.get(Run.class), true);
    }
    return new Execution(run, stepContext);
  }

  private static class Execution extends SynchronousNonBlockingStepExecution<List<RunWrapper>> {
    private final RunWrapper run;

    protected Execution(@Nonnull RunWrapper run, @Nonnull StepContext context) {
      super(context);
      this.run = run;
    }

    @Override
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE",
        justification = "rawBuild can't be null singe getDownstreamBuilds filter out null elements")
    protected List<RunWrapper> run() throws Exception {
      Set<Run> rawBuilds = BuildCache.getCache().getDownstreamBuilds(run.getRawBuild());
      List<RunWrapper> builds = new ArrayList<>();
      for (Run rawBuild : rawBuilds) {
        builds.add(new RunWrapper(rawBuild, false));
      }
      return builds;
    }
  }

  @Extension(optional = true)
  public static final class DescriptorImpl extends StepDescriptor {

    @Override
    public Set<? extends Class<?>> getRequiredContext() {
      return Collections.emptySet();
    }

    @Override
    public String getFunctionName() {
      return "downstreamBuilds";
    }

    @Override
    public String getDisplayName() {
      return "Provide list of downstream builds";
    }
  }
}
