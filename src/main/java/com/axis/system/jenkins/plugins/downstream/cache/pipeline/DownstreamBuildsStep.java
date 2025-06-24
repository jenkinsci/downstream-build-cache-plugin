package com.axis.system.jenkins.plugins.downstream.cache.pipeline;

import com.axis.system.jenkins.plugins.downstream.cache.BuildCache;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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

    protected Execution(@NonNull RunWrapper run, @NonNull StepContext context) {
      super(context);
      this.run = run;
    }

    @Override
    @SuppressFBWarnings(
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
    @NonNull
    public String getDisplayName() {
      return "Provide list of downstream builds";
    }
  }
}
