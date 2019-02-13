package com.axis.system.jenkins.plugins.downstream.cache;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.listeners.ItemListener;

/**
 * Remove all builds from a deleted project. Needed since it does not trigger RunListener.onDelete()
 * for the runs.
 *
 * @author Gustaf Lundh (C) Axis 2018
 */
@Extension
public class ItemListenerImpl extends ItemListener {
  @Override
  public void onDeleted(Item item) {
    super.onDeleted(item);
    if (item instanceof Job) {
      Job<?, ?> job = (Job) item;
      for (Run run : job.getBuilds()) {
        BuildCache.getCache().removeFromCache(run);
      }
    }
  }
}
