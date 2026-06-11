package com.axis.system.jenkins.plugins.downstream.cache.api;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.axis.system.jenkins.plugins.downstream.cache.BuildCache;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.Page;
import org.htmlunit.xml.XmlPage;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/** Tests the {@link DownstreamBuildTreeRootAction} REST endpoint and tree building. */
public class DownstreamBuildTreeRootActionTest {
  @Rule public JenkinsRule j = new JenkinsRule();

  private JSONObject getTree(String project, int build) throws Exception {
    Page page =
        j.createWebClient()
            .goTo(
                DownstreamBuildTreeRootAction.URL_NAME
                    + "/json?project="
                    + project
                    + "&build="
                    + build,
                "application/json");
    return JSONObject.fromObject(page.getWebResponse().getContentAsString());
  }

  @Test
  public void singleDownstreamChildExposesAllFields() throws Exception {
    FreeStyleProject upstream = j.createFreeStyleProject("upstream");
    FreeStyleProject downstream = j.createFreeStyleProject("downstream");
    FreeStyleBuild upstreamBuild = j.buildAndAssertSuccess(upstream);
    FreeStyleBuild downstreamBuild =
        j.assertBuildStatusSuccess(
            downstream.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild)));
    BuildCache.getCache().scheduleReloadCache(true);

    JSONObject root = getTree("upstream", upstreamBuild.getNumber());
    assertEquals("upstream", root.getString("projectFullName"));
    assertEquals(upstreamBuild.getNumber(), root.getInt("number"));
    assertEquals(upstreamBuild.getFullDisplayName(), root.getString("fullName"));
    assertEquals(upstreamBuild.getExternalizableId(), root.getString("externalizableId"));
    assertEquals(upstreamBuild.getUrl(), root.getString("url"));
    assertEquals("SUCCESS", root.getString("result"));
    assertFalse(root.getBoolean("building"));

    JSONArray children = root.getJSONArray("children");
    assertEquals(1, children.size());
    JSONObject child = children.getJSONObject(0);
    assertEquals("downstream", child.getString("projectFullName"));
    assertEquals(downstreamBuild.getNumber(), child.getInt("number"));
    assertEquals(downstreamBuild.getExternalizableId(), child.getString("externalizableId"));
    assertEquals("SUCCESS", child.getString("result"));
    assertTrue(child.getJSONArray("children").isEmpty());
  }

  @Test
  public void fullTreeIsSerializedAcrossMultipleLevels() throws Exception {
    FreeStyleProject parent = j.createFreeStyleProject("parent");
    FreeStyleProject child = j.createFreeStyleProject("child");
    FreeStyleProject grandchild = j.createFreeStyleProject("grandchild");
    FreeStyleBuild parentBuild = j.buildAndAssertSuccess(parent);
    FreeStyleBuild childBuild =
        j.assertBuildStatusSuccess(child.scheduleBuild2(0, new Cause.UpstreamCause(parentBuild)));
    FreeStyleBuild grandchildBuild =
        j.assertBuildStatusSuccess(
            grandchild.scheduleBuild2(0, new Cause.UpstreamCause(childBuild)));
    BuildCache.getCache().scheduleReloadCache(true);

    JSONObject root = getTree("parent", parentBuild.getNumber());
    JSONArray level1 = root.getJSONArray("children");
    assertEquals(1, level1.size());
    JSONObject childNode = level1.getJSONObject(0);
    assertEquals("child", childNode.getString("projectFullName"));

    JSONArray level2 = childNode.getJSONArray("children");
    assertEquals(1, level2.size());
    JSONObject grandchildNode = level2.getJSONObject(0);
    assertEquals("grandchild", grandchildNode.getString("projectFullName"));
    assertEquals(
        grandchildBuild.getExternalizableId(), grandchildNode.getString("externalizableId"));
    assertTrue(grandchildNode.getJSONArray("children").isEmpty());
  }

  @Test
  public void cyclesTerminate() throws Exception {
    FreeStyleProject projectA = j.createFreeStyleProject("a");
    FreeStyleProject projectB = j.createFreeStyleProject("b");
    FreeStyleBuild buildA = j.buildAndAssertSuccess(projectA);
    FreeStyleBuild buildB = j.buildAndAssertSuccess(projectB);
    // Build a downstream cycle a -> b -> a by attaching upstream causes after the fact.
    buildB.addAction(new CauseAction(new Cause.UpstreamCause(buildA)));
    buildA.addAction(new CauseAction(new Cause.UpstreamCause(buildB)));
    BuildCache.getCache().scheduleReloadCache(true);

    JSONObject root = getTree("a", buildA.getNumber());
    JSONArray children = root.getJSONArray("children");
    assertEquals(1, children.size());
    JSONObject bNode = children.getJSONObject(0);
    assertEquals("b", bNode.getString("projectFullName"));
    // b's only downstream is a, which is already visited, so it must not be expanded again.
    assertTrue(bNode.getJSONArray("children").isEmpty());
  }

  @Test
  public void xmlEndpointReturnsTree() throws Exception {
    FreeStyleProject upstream = j.createFreeStyleProject("upstream");
    FreeStyleProject downstream = j.createFreeStyleProject("downstream");
    FreeStyleBuild upstreamBuild = j.buildAndAssertSuccess(upstream);
    j.assertBuildStatusSuccess(
        downstream.scheduleBuild2(0, new Cause.UpstreamCause(upstreamBuild)));
    BuildCache.getCache().scheduleReloadCache(true);

    XmlPage page =
        j.createWebClient()
            .goToXml(
                DownstreamBuildTreeRootAction.URL_NAME
                    + "/xml?project=upstream&build="
                    + upstreamBuild.getNumber());
    String xml = page.getWebResponse().getContentAsString();
    assertThat(xml, containsString("upstream"));
    assertThat(xml, containsString("downstream"));
  }

  @Test
  public void unknownProjectIsNotFound() throws Exception {
    j.createWebClient()
        .assertFails(DownstreamBuildTreeRootAction.URL_NAME + "/json?project=nope&build=1", 404);
  }

  @Test
  public void unknownBuildIsNotFound() throws Exception {
    j.createFreeStyleProject("exists");
    j.createWebClient()
        .assertFails(
            DownstreamBuildTreeRootAction.URL_NAME + "/json?project=exists&build=999", 404);
  }
}
