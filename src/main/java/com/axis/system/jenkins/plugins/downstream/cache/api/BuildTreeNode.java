package com.axis.system.jenkins.plugins.downstream.cache.api;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Result;
import hudson.model.Run;
import java.util.Collections;
import java.util.List;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

/**
 * Immutable, exportable view of a single build/run within a downstream build tree.
 *
 * <p>Each node describes a {@link Run} and references its downstream {@link #getChildren()
 * children} builds, forming the full downstream build tree. Instances are serialized to JSON/XML by
 * the Stapler export framework when served through {@link DownstreamBuildTreeRootAction}.
 *
 * <p>All properties are declared with a high export {@code visibility} so that the complete tree is
 * serialized by default, regardless of nesting depth. Consumers may still trim the response using
 * the standard {@code ?tree=} query parameter.
 *
 * @author Gustaf Lundh (C) Axis 2018
 */
@ExportedBean
public class BuildTreeNode {
  /**
   * Export visibility applied to every property. Chosen large enough that arbitrarily deep trees
   * are fully serialized at the default request depth (the export framework only emits a property
   * at nesting level {@code d} when {@code visibility > d}).
   */
  static final int TREE_VISIBILITY = 999;

  private final String url;
  private final int number;
  private final String fullName;
  private final String externalizableId;
  private final boolean building;
  private final String result;
  private final String projectFullName;
  private final List<BuildTreeNode> children;

  /**
   * Creates a node from a build and its already-built downstream children.
   *
   * @param run the build this node represents
   * @param children the downstream build nodes triggered by {@code run}
   */
  public BuildTreeNode(@NonNull Run<?, ?> run, @NonNull List<BuildTreeNode> children) {
    this.url = run.getUrl();
    this.number = run.getNumber();
    this.fullName = run.getFullDisplayName();
    this.externalizableId = run.getExternalizableId();
    this.building = run.isBuilding();
    Result runResult = run.getResult();
    this.result = runResult == null ? null : runResult.toString();
    this.projectFullName = run.getParent().getFullName();
    this.children = Collections.unmodifiableList(children);
  }

  /**
   * The build URL relative to the Jenkins root (e.g. {@code job/folder/job/MyJob/5/}).
   *
   * @return the relative build URL
   */
  @Exported(visibility = TREE_VISIBILITY)
  public String getUrl() {
    return url;
  }

  /**
   * The build number.
   *
   * @return the build number
   */
  @Exported(visibility = TREE_VISIBILITY)
  public int getNumber() {
    return number;
  }

  /**
   * The full display name of the build (e.g. {@code MyJob #5}).
   *
   * @return the full display name
   */
  @Exported(visibility = TREE_VISIBILITY)
  public String getFullName() {
    return fullName;
  }

  /**
   * The externalizable id uniquely identifying the build (e.g. {@code folder/MyJob#5}).
   *
   * @return the externalizable id
   */
  @Exported(visibility = TREE_VISIBILITY)
  public String getExternalizableId() {
    return externalizableId;
  }

  /**
   * Whether the build is currently in progress.
   *
   * @return {@code true} if the build is still running
   */
  @Exported(visibility = TREE_VISIBILITY)
  public boolean isBuilding() {
    return building;
  }

  /**
   * The build result (e.g. {@code SUCCESS}, {@code FAILURE}, {@code UNSTABLE}, {@code ABORTED}), or
   * {@code null} if the build has not finished.
   *
   * @return the result string, or {@code null} while still building
   */
  @CheckForNull
  @Exported(visibility = TREE_VISIBILITY)
  public String getResult() {
    return result;
  }

  /**
   * The full name of the build's project (e.g. {@code folder/MyJob}).
   *
   * @return the project full name
   */
  @Exported(visibility = TREE_VISIBILITY)
  public String getProjectFullName() {
    return projectFullName;
  }

  /**
   * The downstream builds triggered by this build.
   *
   * @return the immutable list of child nodes (never {@code null})
   */
  @NonNull
  @Exported(visibility = TREE_VISIBILITY)
  public List<BuildTreeNode> getChildren() {
    return children;
  }
}
