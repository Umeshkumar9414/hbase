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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtil;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.net.Address;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.rsgroup.RSGroupInfo;
import org.apache.hadoop.hbase.rsgroup.RSGroupUtil;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.testclassification.MiscTests;
import org.apache.hadoop.hbase.util.RegionMover.RegionMoverBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests RegionMover behaviour when RSGroups are disabled (hbase.balancer.rsgroup.enabled=false, the
 * default). When disabled, the master returns a synthetic default-group RSGroupInfo that contains
 * every online server, so RegionMover must treat all servers as valid targets and unload must
 * complete successfully.
 */
@Tag(MiscTests.TAG)
@Tag(MediumTests.TAG)
public class TestRegionMoverRSGroupDisabled {

  private static final Logger LOG = LoggerFactory.getLogger(TestRegionMoverRSGroupDisabled.class);

  private static final HBaseTestingUtil TEST_UTIL = new HBaseTestingUtil();
  private static final TableName TABLE_NAME = TableName.valueOf("testRegionMoverRSGroupDisabled");

  @BeforeAll
  public static void setUpBeforeClass() throws Exception {
    // RSGroup is intentionally NOT enabled — hbase.balancer.rsgroup.enabled stays false.
    TEST_UTIL.startMiniCluster(3);
  }

  @AfterAll
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @BeforeEach
  public void setUp() throws Exception {
    TableDescriptor td = TableDescriptorBuilder.newBuilder(TABLE_NAME)
      .setColumnFamily(ColumnFamilyDescriptorBuilder.of("f")).build();
    TEST_UTIL.getAdmin().createTable(td, Bytes.toBytes("a"), Bytes.toBytes("z"), 6);
    TEST_UTIL.waitTableAvailable(TABLE_NAME);
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (TEST_UTIL.getAdmin().tableExists(TABLE_NAME)) {
      TEST_UTIL.deleteTable(TABLE_NAME);
    }
  }

  /**
   * With RSGroups disabled, filterRSGroupServers receives a synthetic default-group RSGroupInfo
   * whose members are all online servers. The short-circuit path must return all online servers
   * unchanged.
   */
  @Test
  public void testFilterRSGroupServersReturnsAllServersWhenDisabled() throws Exception {
    assertTrue(!RSGroupUtil.isRSGroupEnabled(TEST_UTIL.getConfiguration()),
      "RSGroup must be disabled for this test");

    List<ServerName> allServers = new ArrayList<>(TEST_UTIL.getAdmin().getRegionServers());
    ServerName any = allServers.get(0);

    try (RegionMover rm =
      new RegionMoverBuilder(any.getHostname() + ":" + any.getPort(), TEST_UTIL.getConfiguration())
        .build()) {

      // Simulate what the master returns via DisabledRSGroupInfoManager:
      // a default-group RSGroupInfo with all online servers already as members.
      RSGroupInfo syntheticDefault = new RSGroupInfo(RSGroupInfo.DEFAULT_GROUP);
      for (ServerName sn : allServers) {
        syntheticDefault.addServer(Address.fromParts(sn.getHostname(), sn.getPort()));
      }

      Collection<ServerName> result = rm.filterRSGroupServers(syntheticDefault, allServers);
      assertEquals(allServers.size(), result.size(),
        "filterRSGroupServers must return all servers when RSGroups are disabled");
      assertTrue(result.containsAll(allServers),
        "filterRSGroupServers result must contain every online server");
    }
  }

  /**
   * End-to-end: unloading a server on a cluster with RSGroups disabled must succeed and leave zero
   * regions on the unloaded server. All servers are valid targets (no group filtering).
   */
  @Test
  public void testUnloadSucceedsWhenRSGroupDisabled() throws Exception {
    assertTrue(!RSGroupUtil.isRSGroupEnabled(TEST_UTIL.getConfiguration()),
      "RSGroup must be disabled for this test");

    // Pick the RS that holds the most table regions as the unload target.
    ServerName target = TEST_UTIL.getMiniHBaseCluster().getRegionServerThreads().stream()
      .map(JVMClusterUtil.RegionServerThread::getRegionServer)
      .filter(rs -> !rs.getRegions(TABLE_NAME).isEmpty()).findFirst().get().getServerName();

    Address targetAddr = Address.fromParts(target.getHostname(), target.getPort());
    LOG.info("Unloading {} (RSGroups disabled)", targetAddr);

    String filename = new Path(TEST_UTIL.getDataTestDir(), "testDisabledRSGroupUnload").toString();
    try (
      RegionMover rm = new RegionMoverBuilder(targetAddr.toString(), TEST_UTIL.getConfiguration())
        .filename(filename).ack(true).build()) {
      rm.unload();
    }

    HRegionServer targetRS = TEST_UTIL.getMiniHBaseCluster().getRegionServerThreads().stream()
      .map(JVMClusterUtil.RegionServerThread::getRegionServer)
      .filter(rs -> rs.getServerName().equals(target)).findFirst().get();

    assertEquals(0, targetRS.getRegions(TABLE_NAME).size(),
      "Unloaded server must hold no table regions after unload");
  }
}
