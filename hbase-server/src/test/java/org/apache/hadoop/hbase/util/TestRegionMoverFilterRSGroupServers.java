/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.hadoop.hbase.HBaseTestingUtil;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.net.Address;
import org.apache.hadoop.hbase.rsgroup.RSGroupInfo;
import org.apache.hadoop.hbase.rsgroup.RSGroupUtil;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.testclassification.MiscTests;
import org.apache.hadoop.hbase.util.RegionMover.RegionMoverBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link RegionMover#filterRSGroupServers}. Spins up a 2-node mini cluster so we can
 * build a real RegionMover instance; the method under test is pure in-memory logic.
 */
@Tag(MiscTests.TAG)
@Tag(MediumTests.TAG)
public class TestRegionMoverFilterRSGroupServers {

  private static final Logger LOG =
    LoggerFactory.getLogger(TestRegionMoverFilterRSGroupServers.class);

  private static final HBaseTestingUtil TEST_UTIL = new HBaseTestingUtil();

  @BeforeAll
  public static void setUpBeforeClass() throws Exception {
    RSGroupUtil.enableRSGroup(TEST_UTIL.getConfiguration());
    TEST_UTIL.startMiniCluster(2);
  }

  @AfterAll
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  private RegionMover buildMover() throws Exception {
    ServerName any = TEST_UTIL.getAdmin().getRegionServers().iterator().next();
    return new RegionMoverBuilder(any.getHostname() + ":" + any.getPort(),
      TEST_UTIL.getConfiguration()).build();
  }

  /**
   * The default-group path short-circuits and returns the onlineServers reference unchanged —
   * members of the RSGroupInfo object are never consulted. Passing an empty RSGroupInfo makes this
   * explicit: if the method iterated members instead of short-circuiting, it would return an empty
   * list and the assertSame would fail.
   */
  @Test
  public void testDefaultGroupReturnsAllServers() throws Exception {
    try (RegionMover rm = buildMover()) {
      List<ServerName> allServers = new ArrayList<>(TEST_UTIL.getAdmin().getRegionServers());
      RSGroupInfo emptyDefault = new RSGroupInfo(RSGroupInfo.DEFAULT_GROUP);
      Collection<ServerName> result = rm.filterRSGroupServers(emptyDefault, allServers);
      assertSame(allServers, result,
        "Default-group short-circuit must return the exact onlineServers reference");
    }
  }

  /** A non-default group with one member must return only that member. */
  @Test
  public void testNonDefaultGroupFiltersToMembers() throws Exception {
    try (RegionMover rm = buildMover()) {
      List<ServerName> allServers = new ArrayList<>(TEST_UTIL.getAdmin().getRegionServers());

      ServerName member = allServers.get(0);
      RSGroupInfo group = new RSGroupInfo("testgroup");
      group.addServer(Address.fromParts(member.getHostname(), member.getPort()));

      Collection<ServerName> result = rm.filterRSGroupServers(group, allServers);
      assertEquals(1, result.size());
      assertTrue(result.contains(member));

      RSGroupInfo defaultGroup = new RSGroupInfo(RSGroupInfo.DEFAULT_GROUP);
      Set<ServerName> defaultGroupRs =
        new HashSet<>(rm.filterRSGroupServers(defaultGroup, allServers));
      assertFalse(defaultGroupRs.contains(member),
        "Default group should not contain test group member");

    }
  }

  /** A non-default group with no matching members must return an empty list. */
  @Test
  public void testNonDefaultGroupWithNoMatchReturnsEmpty() throws Exception {
    try (RegionMover rm = buildMover()) {
      List<ServerName> allServers = new ArrayList<>(TEST_UTIL.getAdmin().getRegionServers());
      RSGroupInfo group = new RSGroupInfo("emptygroup");
      group.addServer(Address.fromParts("nonexistent.host", 9999));
      Collection<ServerName> result = rm.filterRSGroupServers(group, allServers);
      assertTrue(result.isEmpty());
    }
  }
}
