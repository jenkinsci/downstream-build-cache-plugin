package com.axis.system.jenkins.plugins.downstream.cache;

import static hudson.init.InitMilestone.JOB_LOADED;

import hudson.init.Initializer;
import hudson.init.Terminator;
import hudson.model.AbstractItem;
import hudson.model.Cause;
import hudson.model.Cause.UpstreamCause;
import hudson.model.CauseAction;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache responsible for keeping track of the upstream to downstream mapping. The initial scan takes
 * approximately 1 second per 50k builds.
 *
 * @author Gustaf Lundh (C) Axis 2018
 */
public class BuildCache {
  private static final long GC_INTERVAL = TimeUnit.MINUTES.toMillis(10);
  private static final String GC_ENABLE_KEY = BuildCache.class.getCanonicalName() + ".GC_ENABLED";

  private static final Logger logger = LoggerFactory.getLogger(BuildCache.class.getName());

  private final ConcurrentHashMap<String, Set<String>> downstreamBuildCache =
      new ConcurrentHashMap<>();

  private ExecutorService workerThreadPool;
  private final Object workerThreadPoolLock = new Object();

  private final AtomicBoolean isCacheRefreshing = new AtomicBoolean(true);
  private Timer gcTimer;

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
    BuildCache cache = getCache();
    cache.setupWorkerThread();
    cache.scheduleReloadCache(false);
    if (Boolean.getBoolean(GC_ENABLE_KEY)) {
      cache.setupGarbageCollector();
    }
  }

  @Terminator()
  public static void stop() throws Exception {
    BuildCache cache = getCache();
    cache.stopGarbageCollector();
    cache.stopWorkerThread();
  }

  private static boolean isQueueItemCausedBy(Queue.Item item, Run run) {
    if (run == null || item == null) {
      return false;
    }
    return item.getCauses().stream()
        .anyMatch(cause -> cause instanceof UpstreamCause && ((UpstreamCause) cause).pointsTo(run));
  }

  /**
   * Helper method to find downstream Queue.Items triggered by Run.
   *
   * @param run The upstream build
   * @return Queue.Items that has run as upstream cause
   */
  public static Set<Queue.Item> getDownstreamQueueItems(Run run) {
    return getDownstreamQueueItems(Queue.getInstance().getItems(), run);
  }

  /**
   * Helper method to find downstream Queue.Items triggered by Run.
   *
   * <p>Since Queue.getItems() is validating readability permissions on every queue item, this
   * method allows for providing a cached version of the Queue.Items. This is helpful if many Queue
   * downstream look-ups are needed within a very short span of time.
   *
   * @param items The cached version of Queue.getItems()
   * @param run The upstream build
   * @return Queue.Items that has run as upstream cause
   */
  public static Set<Queue.Item> getDownstreamQueueItems(Queue.Item[] items, Run run) {
    Set<Queue.Item> downstreamQueueItems = new TreeSet<>(new QueueTaskComparator());
    // It is guaranteed that a superclass of Run can be put on the Queue. Check if the parent
    // implements Queue.Task.
    if (run.getParent() instanceof Queue.Task) {
      downstreamQueueItems.addAll(
          Arrays.stream(items)
              .filter(it -> isQueueItemCausedBy(it, run))
              .collect(Collectors.toSet()));
    }
    return downstreamQueueItems;
  }

  /**
   * Indicates whether the cache is still building. If still building, the cache may not
   * return all the downstream builds.
   *
   * @return true if cache is not complete.
   */
  public boolean isCacheRefreshing() {
    return isCacheRefreshing.get();
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
      if (cause instanceof Cause.UpstreamCause) {
        if (Jenkins.get().getPlugin("rebuild") != null) {
          if (cause instanceof com.sonyericsson.rebuild.RebuildCause) {
            // Ignore rebuild causes
            continue;
          }
        }
        Cause.UpstreamCause upstreamCause = (Cause.UpstreamCause) cause;

        Job upstreamJob =
            Jenkins.get().getItemByFullName(upstreamCause.getUpstreamProject(), Job.class);
        if (upstreamJob == null) {
          continue;
        }
        upstreamBuilds.add(upstreamJob.getBuildByNumber(upstreamCause.getUpstreamBuild()));
      }
    }
    return upstreamBuilds;
  }

  public void scheduleReloadCache(boolean waitForCompletion) {
    Future future = submitToWorkerThreadPool(this::reloadCache);
    if (waitForCompletion) {
      try {
        future.get();
      } catch (ExecutionException | InterruptedException e) {
        logger.warn("Could not complete task execution", e);
      }
    }
  }

  /**
   * Clears and reloads cache from disk. Should be scheduled on Jenkins startup after jobs are
   * loaded.
   *
   * <p>E.g. @Initializer(after = JOB_LOADED)
   */
  private void reloadCache() {
    logger.info("Building downstream build cache...");
    isCacheRefreshing.set(true);
    downstreamBuildCache.clear();
    // Allow Jenkins to return all jobs, regardless of security setup.
    try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
      for (Job job : Jenkins.get().getAllItems(Job.class)) {
        for (Run run : ((Job<?, ?>) job).getBuilds()) {
          if (workerThreadPool.isShutdown()) {
            logger.info("Worker Thread Pool is shutdown. Stopped reloading the cache!");
            return;
          }
          updateCache(run);
        }
      }
    }
    isCacheRefreshing.set(false);
    logger.info("Building downstream build cache completed!");
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
        if (upstreamBuild == null) {
          continue;
        }
        Set<String> downstreamBuilds =
            downstreamBuildCache.computeIfAbsent(
                upstreamBuild.getExternalizableId(), v -> ConcurrentHashMap.newKeySet());
        downstreamBuilds.add(downstreamRun.getExternalizableId());
      }
    }
  }

  public void doGarbageCollect() {
    logger.info("Running GC...");
    try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
      for (Iterator<String> iter = downstreamBuildCache.keySet().iterator(); iter.hasNext(); ) {
        String key = iter.next();
        if (workerThreadPool.isShutdown()) {
          logger.info("Worker Thread Pool is shutdown. Stopped running GC.");
          break;
        }
        if (Run.fromExternalizableId(key) == null) {
          logger.info("Removing orphan cache entry: " + key);
          iter.remove();
        }
      }
    }
    logger.info("GC completed!");
  }

  private Future submitToWorkerThreadPool(Runnable runnable) {
    synchronized (workerThreadPoolLock) {
      return workerThreadPool.submit(runnable);
    }
  }

  public void setupGarbageCollector() {
    logger.info("Setting up GC scheduling");
    if (gcTimer != null) {
      gcTimer.cancel();
    }
    gcTimer = new Timer();
    gcTimer.scheduleAtFixedRate(
        new TimerTask() {
          @Override
          public void run() {
            if (!isCacheRefreshing()) {
              submitToWorkerThreadPool(BuildCache.this::doGarbageCollect);
            } else {
              logger.info("Cache is still refreshing, did not submit GC-task to worker pool");
            }
          }
        },
        GC_INTERVAL,
        GC_INTERVAL);
  }

  public void stopGarbageCollector() {
    logger.info("Stopping GC scheduling");
    if (gcTimer != null) {
      gcTimer.cancel();
    }
  }

  public void setupWorkerThread() {
    logger.info("Setting up worker thread pool");
    synchronized (workerThreadPoolLock) {
      if (workerThreadPool == null || workerThreadPool.isShutdown()) {
        workerThreadPool =
            Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "BuildCache Worker Thread"));
      }
    }
  }

  /**
   * The using awaitTermination() is necessary, because if we allow Jenkins to continue shutting
   * down while still refreshing the cache, the Jenkins.getInstance() will start returning null, all
   * while we still try to examine builds. While most likely harmless, this will trigger loads of
   * exceptions in the log.
   *
   * <p>We also avoid @see ExecutorService.shutdownNow(), since the InterruptedException will most
   * likely be silenced by Jenkins in RunMap.retrieve(), where much time is spent when refreshing
   * the cache.
   */
  public void stopWorkerThread() {
    logger.info("Stopping Worker Thread Pool...");
    synchronized (workerThreadPoolLock) {
      workerThreadPool.shutdown();
      try {
        // Wait a while for existing tasks to terminate
        if (!workerThreadPool.awaitTermination(120, TimeUnit.SECONDS)) {
          logger.warn("Worker Thread Pool did not terminate gracefully!");
        }
      } catch (InterruptedException ie) {
        // Preserve interrupt status
        Thread.currentThread().interrupt();
      }
    }
    logger.info("Worker Thread Pool stopped!");
  }

  /**
   * Returns all downstream builds triggered by the upstream builds.
   *
   * @param run The upstream build
   * @return Downstream builds or empty set if none is found
   */
  public Set<Run> getDownstreamBuilds(Run run) {
    Set<Run> downstreamBuilds = new TreeSet<>(new BuildComparator());
    for (String id :
        downstreamBuildCache.getOrDefault(run.getExternalizableId(), Collections.emptySet())) {
      Run downstreamRun = Run.fromExternalizableId(id);
      if (downstreamRun != null) {
        downstreamBuilds.add(downstreamRun);
      }
    }
    return downstreamBuilds;
  }

  /**
   * Builds a summary report of the cache
   *
   * @return A summarized view of the content of the cache
   */
  public String getStatistics() {
    return "Is Cache Refreshing: " +
            isCacheRefreshing.get() +
            '\n' +
            "Number of upstream builds: " +
            downstreamBuildCache.size() +
            '\n' +
            "Number of downstream builds: " +
            downstreamBuildCache.values().stream().mapToInt(Set::size).sum();
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

  private static class InstanceHolder {
    public static BuildCache INSTANCE = new BuildCache();
  }

  /**
   * Allows returned downstreamBuild Sets to be sorted first by project name and then by number. The
   * default comparator for Run prioritizes build numbers before project name.
   */
  public static class BuildComparator implements Comparator<Run>, Serializable {

    @Override
    public int compare(Run r1, Run r2) {
      int res = r1.getParent().getFullName().compareTo(r2.getParent().getFullName());
      if (res == 0) {
        return r1.getNumber() - r2.getNumber();
      }
      return res;
    }
  }

  public static class QueueTaskComparator implements Comparator<Queue.Item>, Serializable {

    @Override
    public int compare(Queue.Item i1, Queue.Item i2) {
      if (i1.task instanceof AbstractItem && i2.task instanceof AbstractItem) {
        return ((AbstractItem) i1.task)
            .getFullName()
            .compareTo(((AbstractItem) i2.task).getFullName());
      }
      return i1.task.getFullDisplayName().compareTo(i2.task.getFullDisplayName());
    }
  }
}
