/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.projectbranch.ws;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.System2;
import org.sonar.api.web.UserRole;
import org.sonar.db.DbTester;
import org.sonar.db.component.BranchType;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.metric.MetricDto;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.WsActionTester;
import org.sonarqube.ws.Common;
import org.sonarqube.ws.MediaTypes;
import org.sonarqube.ws.WsBranches;
import org.sonarqube.ws.WsBranches.Branch;
import org.sonarqube.ws.WsBranches.ListWsResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.sonar.api.measures.CoreMetrics.ALERT_STATUS_KEY;
import static org.sonar.api.measures.CoreMetrics.BUGS_KEY;
import static org.sonar.api.measures.CoreMetrics.CODE_SMELLS_KEY;
import static org.sonar.api.measures.CoreMetrics.VULNERABILITIES_KEY;
import static org.sonar.test.JsonAssert.assertJson;
import static org.sonarqube.ws.WsBranches.Branch.Status;

public class ListActionTest {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Rule
  public DbTester db = DbTester.create(System2.INSTANCE);

  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();

  private MetricDto qualityGateStatus;
  private MetricDto bugs;
  private MetricDto vulnerabilities;
  private MetricDto codeSmells;

  public WsActionTester tester = new WsActionTester(new ListAction(db.getDbClient(), userSession));

  @Before
  public void setUp() throws Exception {
    qualityGateStatus = db.measures().insertMetric(m -> m.setKey(ALERT_STATUS_KEY));
    bugs = db.measures().insertMetric(m -> m.setKey(BUGS_KEY));
    vulnerabilities = db.measures().insertMetric(m -> m.setKey(VULNERABILITIES_KEY));
    codeSmells = db.measures().insertMetric(m -> m.setKey(CODE_SMELLS_KEY));
  }

  @Test
  public void test_definition() {
    WebService.Action definition = tester.getDef();
    assertThat(definition.key()).isEqualTo("list");
    assertThat(definition.isPost()).isFalse();
    assertThat(definition.isInternal()).isTrue();
    assertThat(definition.params()).extracting(WebService.Param::key).containsExactlyInAnyOrder("project");
    assertThat(definition.since()).isEqualTo("6.6");
  }

  @Test
  public void fail_if_missing_project_parameter() {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("The 'project' parameter is missing");

    tester.newRequest().execute();
  }

  @Test
  public void fail_if_not_a_reference_on_project() {
    ComponentDto project = db.components().insertPrivateProject();
    ComponentDto file = db.components().insertComponent(ComponentTesting.newFileDto(project));
    userSession.logIn().addProjectPermission(UserRole.USER, project);

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Invalid project key");

    tester.newRequest()
      .setParam("project", file.getDbKey())
      .execute();
  }

  @Test
  public void fail_if_project_does_not_exist() {
    expectedException.expect(NotFoundException.class);
    expectedException.expectMessage("Project key 'foo' not found");

    tester.newRequest()
      .setParam("project", "foo")
      .execute();
  }

  @Test
  public void main_branch() {
    ComponentDto project = db.components().insertMainBranch();
    userSession.logIn().addProjectPermission(UserRole.USER, project);

    ListWsResponse response = tester.newRequest()
      .setParam("project", project.getDbKey())
      .executeProtobuf(ListWsResponse.class);

    assertThat(response.getBranchesList())
      .extracting(Branch::hasName, Branch::getIsMain, Branch::getType)
      .containsExactlyInAnyOrder(tuple(false, true, Common.BranchType.LONG));
  }

  @Test
  public void test_project_with_zero_branches() {
    ComponentDto project = db.components().insertPrivateProject();
    userSession.logIn().addProjectPermission(UserRole.USER, project);

    String json = tester.newRequest()
      .setParam("project", project.getDbKey())
      .setMediaType(MediaTypes.JSON)
      .execute()
      .getInput();
    assertJson(json).isSimilarTo("{\"branches\": []}");
  }

  @Test
  public void test_project_with_branches() {
    ComponentDto project = db.components().insertMainBranch();
    db.components().insertProjectBranch(project, b -> b.setKey("feature/bar"));
    db.components().insertProjectBranch(project, b -> b.setKey("feature/foo"));
    userSession.logIn().addProjectPermission(UserRole.USER, project);

    ListWsResponse response = tester.newRequest()
      .setParam("project", project.getDbKey())
      .executeProtobuf(ListWsResponse.class);

    assertThat(response.getBranchesList())
      .extracting(Branch::getName, Branch::getType)
      .containsExactlyInAnyOrder(
        tuple("", Common.BranchType.LONG),
        tuple("feature/foo", Common.BranchType.LONG),
        tuple("feature/bar", Common.BranchType.LONG));
  }

  @Test
  public void short_living_branches() {
    ComponentDto project = db.components().insertMainBranch();
    userSession.logIn().addProjectPermission(UserRole.USER, project);
    ComponentDto longLivingBranch = db.components().insertProjectBranch(project,
      b -> b.setKey("long").setBranchType(BranchType.LONG));
    ComponentDto shortLivingBranch = db.components().insertProjectBranch(project,
      b -> b.setKey("short").setBranchType(BranchType.SHORT).setMergeBranchUuid(longLivingBranch.uuid()));
    ComponentDto shortLivingBranchOnMaster = db.components().insertProjectBranch(project,
      b -> b.setKey("short_on_master").setBranchType(BranchType.SHORT).setMergeBranchUuid(project.uuid()));

    ListWsResponse response = tester.newRequest()
      .setParam("project", project.getKey())
      .executeProtobuf(ListWsResponse.class);

    assertThat(response.getBranchesList())
      .extracting(Branch::getName, Branch::getType, Branch::getMergeBranch)
      .containsExactlyInAnyOrder(
        tuple("", Common.BranchType.LONG, ""),
        tuple(longLivingBranch.getBranch(), Common.BranchType.LONG, ""),
        tuple(shortLivingBranch.getBranch(), Common.BranchType.SHORT, longLivingBranch.getBranch()),
        tuple(shortLivingBranchOnMaster.getBranch(), Common.BranchType.SHORT, ""));
  }

  @Test
  public void quality_gate_status_on_long_living_branch() {
    ComponentDto project = db.components().insertMainBranch();
    userSession.logIn().addProjectPermission(UserRole.USER, project);
    ComponentDto branch = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.LONG));
    SnapshotDto branchAnalysis = db.components().insertSnapshot(branch);
    db.measures().insertMeasure(branch, branchAnalysis, qualityGateStatus, m -> m.setData("OK"));

    ListWsResponse response = tester.newRequest()
      .setParam("project", project.getKey())
      .executeProtobuf(ListWsResponse.class);

    assertThat(response.getBranchesList())
      .extracting(b -> b.getStatus().hasQualityGateStatus(), b -> b.getStatus().getQualityGateStatus())
      .containsExactlyInAnyOrder(tuple(false, ""), tuple(true, "OK"));
  }

  @Test
  public void bugs_vulnerabilities_and_code_smells_on_short_living_branch() {
    ComponentDto project = db.components().insertMainBranch();
    userSession.logIn().addProjectPermission(UserRole.USER, project);
    ComponentDto longLivingBranch = db.components().insertProjectBranch(project, b -> b.setBranchType(BranchType.LONG));
    ComponentDto shortLivingBranch = db.components().insertProjectBranch(project,
      b -> b.setBranchType(BranchType.SHORT).setMergeBranchUuid(longLivingBranch.uuid()));
    SnapshotDto branchAnalysis = db.components().insertSnapshot(shortLivingBranch);
    db.measures().insertMeasure(shortLivingBranch, branchAnalysis, bugs, m -> m.setValue(1d));
    db.measures().insertMeasure(shortLivingBranch, branchAnalysis, vulnerabilities, m -> m.setValue(2d));
    db.measures().insertMeasure(shortLivingBranch, branchAnalysis, codeSmells, m -> m.setValue(3d));

    ListWsResponse response = tester.newRequest()
      .setParam("project", project.getKey())
      .executeProtobuf(ListWsResponse.class);

    assertThat(response.getBranchesList().stream().map(WsBranches.Branch::getStatus))
      .extracting(Status::hasBugs, Status::getBugs, Status::hasVulnerabilities, Status::getVulnerabilities, Status::hasCodeSmells, Status::getCodeSmells)
      .containsExactlyInAnyOrder(
        tuple(false, 0, false, 0, false, 0),
        tuple(false, 0, false, 0, false, 0),
        tuple(true, 1, true, 2, true, 3));
  }

  @Test
  public void test_example() {
    ComponentDto project = db.components().insertPrivateProject(p -> p.setDbKey("sonarqube"));
    ComponentDto longLivingBranch = db.components().insertProjectBranch(project, b -> b.setKey("feature/bar").setBranchType(BranchType.LONG));
    db.components().insertProjectBranch(project, b -> b.setKey("feature/foo").setBranchType(BranchType.SHORT).setMergeBranchUuid(longLivingBranch.uuid()));
    userSession.logIn().addProjectPermission(UserRole.USER, project);

    String json = tester.newRequest()
      .setParam("project", project.getDbKey())
      .execute()
      .getInput();

    assertJson(json).isSimilarTo(tester.getDef().responseExampleAsString());
  }

}