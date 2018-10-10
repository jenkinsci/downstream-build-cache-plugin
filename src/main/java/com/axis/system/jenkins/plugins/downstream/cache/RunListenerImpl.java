package com.axis.system.jenkins.plugins.downstream.cache;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

/**
 * Cache Downstream Graph from scheduled builds by intercepting UpstreamCauses of newly scheduled
 * builds.
 *
 * @author Gustaf Lundh <gustaf.lundh@axis.com>
 */
@Extension
public class RunListenerImpl extends RunListener<Run> {
  @Override
  public void onStarted(final Run r, TaskListener listener) {
    BuildCache.getCache().updateCache(r);
  }

  @Override
  public void onDeleted(Run r) {
    BuildCache.getCache().removeFromCache(r);
  }
}
