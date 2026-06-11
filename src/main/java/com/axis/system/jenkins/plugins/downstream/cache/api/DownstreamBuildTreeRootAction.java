package com.axis.system.jenkins.plugins.downstream.cache.api;

import com.axis.system.jenkins.plugins.downstream.cache.BuildCache;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.RootAction;
import hudson.model.Run;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.servlet.ServletException;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Flavor;
import org.kohsuke.stapler.verb.GET;

/**
 * REST/HTTP API exposing the full downstream build tree for a given build.
 *
 * <p>Given a project full name and a build number, the endpoint resolves the build and returns the
 * complete downstream build tree (see {@link BuildTreeNode}) serialized by the Stapler export
 * framework.
 *
 * <ul>
 *   <li>{@code GET /downstream-build-tree/json?project=<fullName>&build=<number>}
 *   <li>{@code GET /downstream-build-tree/xml?project=<fullName>&build=<number>}
 * </ul>
 *
 * <p>The standard export query parameter {@code ?tree=} is honored to trim the response. Builds the
 * current user is not allowed to read ({@link Item#READ}) are omitted from the tree, and an unknown
 * or unreadable root build results in {@code 404}.
 *
 * @author Gustaf Lundh (C) Axis 2018
 */
@Extension
public class DownstreamBuildTreeRootAction implements RootAction {

  static final String URL_NAME = "downstream-build-tree";

  @Override
  @CheckForNull
  public String getIconFileName() {
    return null;
  }

  @Override
  @CheckForNull
  public String getDisplayName() {
    return null;
  }

  @Override
  @NonNull
  public String getUrlName() {
    return URL_NAME;
  }

  /**
   * Serves the downstream build tree as JSON.
   *
   * @param req the current request
   * @param rsp the current response
   * @param project the full name of the root project
   * @param build the build number of the root build
   * @throws ServletException if serialization fails
   * @throws IOException if writing the response fails
   */
  @GET
  public void doJson(
      StaplerRequest req,
      StaplerResponse rsp,
      @QueryParameter String project,
      @QueryParameter int build)
      throws ServletException, IOException {
    serve(req, rsp, project, build, Flavor.JSON);
  }

  /**
   * Serves the downstream build tree as XML.
   *
   * @param req the current request
   * @param rsp the current response
   * @param project the full name of the root project
   * @param build the build number of the root build
   * @throws ServletException if serialization fails
   * @throws IOException if writing the response fails
   */
  @GET
  public void doXml(
      StaplerRequest req,
      StaplerResponse rsp,
      @QueryParameter String project,
      @QueryParameter int build)
      throws ServletException, IOException {
    serve(req, rsp, project, build, Flavor.XML);
  }

  private void serve(
      StaplerRequest req, StaplerResponse rsp, String project, int build, Flavor flavor)
      throws ServletException, IOException {
    Run<?, ?> root = resolveRun(project, build);
    if (root == null) {
      rsp.sendError(
          StaplerResponse.SC_NOT_FOUND,
          "No readable build found for project '" + Util.fixNull(project) + "' build #" + build);
      return;
    }
    rsp.serveExposedBean(req, buildTree(root), flavor);
  }

  @CheckForNull
  private Run<?, ?> resolveRun(@CheckForNull String project, int build) {
    if (Util.fixEmptyAndTrim(project) == null) {
      return null;
    }
    // getItemByFullName enforces Item.READ for the current user, returning null when not allowed.
    Job<?, ?> job = Jenkins.get().getItemByFullName(project, Job.class);
    if (job == null) {
      return null;
    }
    return job.getBuildByNumber(build);
  }

  /**
   * Builds the full downstream build tree rooted at the given build.
   *
   * <p>Traversal tracks visited builds by externalizable id so that an already-visited build is not
   * expanded again, which keeps the tree finite even when the downstream graph contains cycles.
   * Builds the current user cannot read are skipped.
   *
   * @param root the root build
   * @return the root node of the downstream build tree
   */
  @NonNull
  static BuildTreeNode buildTree(@NonNull Run<?, ?> root) {
    Set<String> visited = new HashSet<>();
    visited.add(root.getExternalizableId());
    return buildNode(root, visited);
  }

  @NonNull
  private static BuildTreeNode buildNode(@NonNull Run<?, ?> run, @NonNull Set<String> visited) {
    List<BuildTreeNode> children = new ArrayList<>();
    for (Run downstream : BuildCache.getCache().getDownstreamBuilds(run)) {
      if (!downstream.getParent().hasPermission(Item.READ)) {
        continue;
      }
      if (!visited.add(downstream.getExternalizableId())) {
        continue;
      }
      children.add(buildNode(downstream, visited));
    }
    return new BuildTreeNode(run, children);
  }
}
