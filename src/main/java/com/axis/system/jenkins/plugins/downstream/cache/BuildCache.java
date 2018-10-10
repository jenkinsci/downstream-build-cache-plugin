package com.axis.system.jenkins.plugins.downstream.cache;

import static hudson.init.InitMilestone.JOB_LOADED;

import hudson.init.Initializer;
import hudson.init.Terminator;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Job;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache responsible for keeping track of the upstream to downstream mapping. The initial scan takes
 * approximately 1 second per 50k builds.
 *
 * @author Gustaf Lundh <gustaf.lundh@axis.com>
 */
public class BuildCache {
  private static final Logger LOGGER = LoggerFactory.getLogger(BuildCache.class.getName());
  private static final long GC_INTERVAL = TimeUnit.MINUTES.toMillis(10);

  private final ConcurrentHashMap<String, Set<String>> downstreamBuildCache =
      new ConcurrentHashMap<>();

  private Timer timer;

  private static class InstanceHolder {
    public static BuildCache INSTANCE = new BuildCache();
  }

  /**
   * Returns the instance of this class.
   *
   * @return the instance.
   */
  public static BuildCache getCache() {
    return InstanceHolder.INSTANCE;
  }

  /** Initialize the cache after all jobs are loaded. */
  @Initializer(after = JOB_LOADED)
  public static void initCache() {
    LOGGER.info("Building downstream builds cache...");
    getCache().reloadCache();
    LOGGER.info("Building downstream builds cache completed!");
    getCache().startGarbageCollector();
  }

  @Terminator()
  public static void stop() throws Exception {
    getCache().stopGarbageCollector();
  }

  /**
   * Convenience method for parsing UpstreamCauses from a CauseAction.
   *
   * @param causeAction the downstream builds CauseAction
   * @return the upstream builds
   */
  private List<Run> getUpstreamBuilds(CauseAction causeAction) {
    List<Run> upstreamBuilds = new ArrayList<>();
    for (Cause cause : causeAction.getCauses()) {
      if (Cause.UpstreamCause.class.isInstance(cause)) {

        Cause.UpstreamCause upstreamCause = (Cause.UpstreamCause) cause;

        Job upstreamJob =
            Jenkins.getInstance().getItemByFullName(upstreamCause.getUpstreamProject(), Job.class);
        if (upstreamJob == null) {
          continue;
        }
        upstreamBuilds.add(upstreamJob.getBuildByNumber(upstreamCause.getUpstreamBuild()));
      }
    }
    return upstreamBuilds;
  }

  /**
   * Clears and reloads cache from disk. Should be executed on Jenkins startup after builds are
   * loaded.
   *
   * <p>E.g. @Initializer(after = JOB_LOADED)
   */
  public void reloadCache() {
    downstreamBuildCache.clear();

    // Allow Jenkins to return all jobs, regardless of security setup.
    try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
      for (Job job : Jenkins.getInstance().getAllItems(Job.class)) {
        for (Run run : ((Job<?, ?>) job).getBuilds()) {
          updateCache(run);
        }
      }
    }
  }

  /**
   * Parses upstream causes in a downstream build and updates the cache.
   *
   * @param downstreamRun Downstream build to update the cache with
   */
  protected void updateCache(Run downstreamRun) {
    for (CauseAction causeAction : downstreamRun.getActions(CauseAction.class)) {
      List<Run> upstreamBuilds = getUpstreamBuilds(causeAction);

      for (Run upstreamBuild : upstreamBuilds) {
        /**
         * We check for identical parents since Rebuilder-plugin defines the retriggered build as
         * upstream cause, which can lead to some strange side effects in the visualization.
         */
        if (upstreamBuild == null || upstreamBuild.getParent() == downstreamRun.getParent()) {
          continue;
        }
        Set<String> downstreamBuilds =
            downstreamBuildCache.computeIfAbsent(
                upstreamBuild.getExternalizableId(), v -> new HashSet<>());
        downstreamBuilds.add(downstreamRun.getExternalizableId());
      }
    }
  }

  public void doGarbageCollect() {
    try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
      downstreamBuildCache
          .entrySet()
          .removeIf(
              e -> {
                if (Run.fromExternalizableId(e.getKey()) == null) {
                  LOGGER.info(e.getKey() + " will be GC:ed");
                  return true;
                }
                return false;
              });
    }
  }

  public synchronized void stopGarbageCollector() {
    LOGGER.info("Stopping GC scheduling");
    if (timer != null) {
      timer.cancel();
    }
  }

  public synchronized void startGarbageCollector() {
    LOGGER.info("Setting up GC scheduling");
    if (timer == null) {
      timer = new Timer();
    } else {
      timer.cancel();
      timer = new Timer();
    }
    timer.scheduleAtFixedRate(
        new TimerTask() {
          @Override
          public void run() {
            doGarbageCollect();
          }
        },
        GC_INTERVAL,
        GC_INTERVAL);
  }

  /**
   * Returns all downstream builds triggered by the upstream builds.
   *
   * @param run The upstream build
   * @return Downstream builds or empty set if none is found
   */
  public Set<Run> getDownstreamBuilds(Run run) {
    Set<Run> downstreamBuilds = new HashSet<>();
    for (String id :
        downstreamBuildCache.getOrDefault(run.getExternalizableId(), Collections.emptySet())) {
      Run downstreamRun = Run.fromExternalizableId(id);
      if (downstreamRun != null) {
        downstreamBuilds.add(downstreamRun);
      }
    }
    return downstreamBuilds;
  }

  /** Builds a summary report of the cache */
  public String getStatistics() {
    StringBuilder sb =
        new StringBuilder()
            .append("Number of upstream builds: ")
            .append(downstreamBuildCache.size())
            .append("\n")
            .append("Number of downstream builds: ")
            .append(downstreamBuildCache.values().stream().mapToInt(v -> v.size()).sum());
    return sb.toString();
  }

  /**
   * Remove a run from the cache. First from values (downstream builds) and then the key (upstream
   * build).
   *
   * @param run The run to remove from the cache.
   */
  protected void removeFromCache(Run run) {
    for (CauseAction causeAction : run.getActions(CauseAction.class)) {
      List<Run> upstreamBuilds = getUpstreamBuilds(causeAction);

      for (Run upstreamBuild : upstreamBuilds) {
        if (upstreamBuild != null) {
          Set<String> downstreamBuilds =
              downstreamBuildCache.get(upstreamBuild.getExternalizableId());
          if (downstreamBuilds != null) {
            downstreamBuilds.remove(run.getExternalizableId());
          }
        }
      }
    }
    downstreamBuildCache.remove(run.getExternalizableId());
  }

  /**
   * Fetches all downstream builds belonging to the specified project.
   *
   * @param upstreamBuild The upstream build
   * @param downstreamProject The downstream project
   * @return Downstream builds belonging to the upstream build and filtered by the downstream
   *     project.
   */
  public Set<Run> getDownstreamBuildsByProject(Run upstreamBuild, Job downstreamProject) {
    Set<Run> filteredDownstreamBuilds = new TreeSet<>();
    if (downstreamProject == null) {
      return filteredDownstreamBuilds;
    }
    for (Run downstreamBuild : getDownstreamBuilds(upstreamBuild)) {
      if (downstreamBuild.getParent() == downstreamProject) {
        filteredDownstreamBuilds.add(downstreamBuild);
      }
    }
    return filteredDownstreamBuilds;
  }
}
