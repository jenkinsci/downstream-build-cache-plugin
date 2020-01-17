package com.axis.system.jenkins.plugins.downstream.cache;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;

import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Tests cache consistency and logic.
 *
 * @author Gustaf Lundh (C) Axis 2018
 */
public class BuildCacheTest {
  @Rule public JenkinsRule j = new JenkinsRule();

  /**
   * Exercise cache building. One downstream build.
   *
   * @throws Exception
   */
  @Test
  public void reloadCacheOneDownstreamBuild() throws Exception {
    FreeStyleProject upstreamProject = j.createFreeStyleProject();
    FreeStyleProject downstreamProject = j.createFreeStyleProject();
    Run upstreamBuild = upstreamProject.scheduleBuild2(0).get();
    Run downstreamBuild =
        downstreamProject.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild)).get();
    BuildCache.getCache().scheduleReloadCache(true);
    BuildCache cache = BuildCache.getCache();
    assertThat(
        "Wrong number of expected builds in cache",
        cache.getDownstreamBuilds(upstreamBuild).size(),
        is(1));
    assertThat(
        "Wrong expected build in cache",
        cache.getDownstreamBuilds(upstreamBuild),
        hasItem(downstreamBuild));
  }

  /**
   * Downstream task in Queue (WaitingItems)
   *
   * @throws Exception
   */
  @Test
  public void downstreamTaskInQueue() throws Exception {
    FreeStyleProject upstreamProject = j.createFreeStyleProject();
    FreeStyleProject downstreamProject = j.createFreeStyleProject();
    Run upstreamBuild = upstreamProject.scheduleBuild2(0).get();
    downstreamProject.scheduleBuild2(10, new Cause.UpstreamCause(upstreamBuild));
    assertThat(
        "Wrong number of expected builds in queue",
        BuildCache.getDownstreamQueueItems(upstreamBuild).size(),
        is(1));
    assertThat(
        "Wrong expected build in cache",
        BuildCache.getDownstreamQueueItems(upstreamBuild).iterator().next().task,
        is(downstreamProject));
  }

  /**
   * Exercise cache building. Several downstream builds.
   *
   * @throws Exception
   */
  @Test
  public void reloadCacheMultipleDownstreamBuilds() throws Exception {
    FreeStyleProject upstreamProject = j.createFreeStyleProject();
    FreeStyleProject downstreamProjectA = j.createFreeStyleProject();
    FreeStyleProject downstreamProjectB = j.createFreeStyleProject();
    Run upstreamBuild = upstreamProject.scheduleBuild2(0).get();
    Run downstreamBuildA =
        downstreamProjectA.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild)).get();
    Run downstreamBuildB =
        downstreamProjectB.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild)).get();
    BuildCache.getCache().scheduleReloadCache(true);
    BuildCache cache = BuildCache.getCache();
    assertThat(
        "Wrong number of expected builds in cache",
        cache.getDownstreamBuilds(upstreamBuild).size(),
        is(2));
    assertThat(
        "Wrong expected build in cache",
        cache.getDownstreamBuilds(upstreamBuild),
        hasItem(downstreamBuildA));
    assertThat(
        "Wrong expected build in cache",
        cache.getDownstreamBuilds(upstreamBuild),
        hasItem(downstreamBuildB));
  }

  /**
   * Fetch downstream builds filtered by project.
   *
   * @throws Exception
   */
  @Test
  public void getDownstreamBuildsByProject() throws Exception {
    FreeStyleProject upstreamProject = j.createFreeStyleProject();
    FreeStyleProject downstreamProjectA = j.createFreeStyleProject();
    FreeStyleProject downstreamProjectB = j.createFreeStyleProject();
    Run upstreamBuild = upstreamProject.scheduleBuild2(0).get();
    Run downstreamBuildA =
        downstreamProjectA.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild)).get();
    downstreamProjectB.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild)).get();
    BuildCache cache = BuildCache.getCache();
    assertThat(
        "Wrong number of expected builds returned",
        cache.getDownstreamBuildsByProject(upstreamBuild, downstreamProjectA).size(),
        is(1));
    assertThat(
        "Wrong expected build in cache",
        cache.getDownstreamBuildsByProject(upstreamBuild, downstreamProjectA),
        hasItem(downstreamBuildA));
  }

  /**
   * Remove one downstream build from cache.
   *
   * @throws Exception
   */
  @Test
  public void removeDownstreamBuildFromCache() throws Exception {
    FreeStyleProject upstreamProject = j.createFreeStyleProject();
    FreeStyleProject downstreamProjectA = j.createFreeStyleProject();
    FreeStyleProject downstreamProjectB = j.createFreeStyleProject();
    Run upstreamBuild = upstreamProject.scheduleBuild2(0).get();
    Run downstreamBuildA =
        downstreamProjectA.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild)).get();
    Run downstreamBuildB =
        downstreamProjectB.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild)).get();
    BuildCache cache = BuildCache.getCache();
    cache.removeFromCache(downstreamBuildB);
    assertThat(
        "Wrong number of expected builds returned",
        cache.getDownstreamBuildsByProject(upstreamBuild, downstreamProjectA).size(),
        is(1));
    assertThat(
        "Removed downstream build still in cache",
        cache.getDownstreamBuildsByProject(upstreamBuild, downstreamProjectA),
        not(hasItem(downstreamBuildB)));
    assertThat(
        "Expected downstream build not in cache",
        cache.getDownstreamBuildsByProject(upstreamBuild, downstreamProjectA),
        hasItem(downstreamBuildA));
  }

  /**
   * Remove one upstream build from cache.
   *
   * @throws Exception
   */
  @Test
  public void removeUpstreamBuildFromCache() throws Exception {
    FreeStyleProject upstreamProjectA = j.createFreeStyleProject();
    FreeStyleProject downstreamProjectA = j.createFreeStyleProject();
    FreeStyleProject upstreamProjectB = j.createFreeStyleProject();
    FreeStyleProject downstreamProjectB = j.createFreeStyleProject();
    Run upstreamBuildA = upstreamProjectA.scheduleBuild2(0).get();
    Run upstreamBuildB = upstreamProjectB.scheduleBuild2(0).get();
    Run downstreamBuildA =
        downstreamProjectA.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuildA)).get();
    downstreamProjectB.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuildB)).get();
    BuildCache cache = BuildCache.getCache();
    cache.removeFromCache(upstreamBuildB);
    assertThat(
        "Removed upstream build still in cache",
        cache.getDownstreamBuilds(upstreamBuildB).size(),
        is(0));
    assertThat(
        "Expected build not in cache",
        cache.getDownstreamBuilds(upstreamBuildA),
        hasItem(downstreamBuildA));
  }

  /**
   * Update cache with a new entry.
   *
   * @throws Exception
   */
  @Test
  public void updateCache() throws Exception {
    FreeStyleProject upstreamProject = j.createFreeStyleProject();
    FreeStyleProject downstreamProject = j.createFreeStyleProject();
    Run upstreamBuild = upstreamProject.scheduleBuild2(0).get();
    Run downstreamBuild = downstreamProject.scheduleBuild2(0).get();
    // Get around the runListener automatically register the build in the cache
    // by adding the upstream cause afterwards.
    downstreamBuild.addAction(new CauseAction(new Cause.UpstreamCause(upstreamBuild)));
    BuildCache cache = BuildCache.getCache();
    cache.updateCache(downstreamBuild);
    assertThat(
        "Expected build not in cache",
        cache.getDownstreamBuilds(upstreamBuild),
        hasItem(downstreamBuild));
  }
}
