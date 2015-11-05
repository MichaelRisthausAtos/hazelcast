/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map.impl.query;

import com.hazelcast.config.Config;
import com.hazelcast.config.MapIndexConfig;
import com.hazelcast.config.MapStoreConfig;
import com.hazelcast.core.EntryAdapter;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.MapStoreAdapter;
import com.hazelcast.nio.serialization.Portable;
import com.hazelcast.nio.serialization.PortableFactory;
import com.hazelcast.query.SampleObjects;
import com.hazelcast.query.SampleObjects.Employee;
import com.hazelcast.query.SampleObjects.ValueType;
import com.hazelcast.query.SqlPredicate;
import com.hazelcast.test.AssertTask;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static com.hazelcast.test.TimeConstants.MINUTE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastSerialClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class QueryAdvancedTest extends HazelcastTestSupport {

    @Test(timeout = MINUTE)
    public void testQueryWithTTL() throws Exception {

        Config cfg = getConfig();
        String mapName = "default";
        cfg.getMapConfig(mapName).setTimeToLiveSeconds(5);

        HazelcastInstance h1 = createHazelcastInstance(cfg);

        IMap imap = h1.getMap(mapName);
        imap.addIndex("name", false);
        imap.addIndex("age", false);
        imap.addIndex("active", true);

        int passiveEmployees = 5;
        int activeEmployees = 5;
        int allEmployees = passiveEmployees + activeEmployees;

        final CountDownLatch latch = new CountDownLatch(allEmployees);
        imap.addEntryListener(new EntryAdapter() {
            @Override
            public void entryEvicted(EntryEvent event) {
                latch.countDown();
            }
        }, false);

        for (int i = 0; i < activeEmployees; i++) {
            Employee employee = new Employee("activeEmployee" + i, 60, true, Double.valueOf(i));
            imap.put("activeEmployee" + i, employee);
        }

        for (int i = 0; i < passiveEmployees; i++) {
            Employee employee = new Employee("passiveEmployee" + i, 60, false, Double.valueOf(i));
            imap.put("passiveEmployee" + i, employee);
        }

        //check the query result before eviction
        Collection values = imap.values(new SqlPredicate("active"));
        assertEquals(activeEmployees, values.size());

        //wait until eviction is completed
        assertOpenEventually(latch);

        //check the query result after eviction
        values = imap.values(new SqlPredicate("active"));
        assertEquals(0, values.size());
    }

    @Test(timeout = MINUTE)
    public void testTwoNodesWithPartialIndexes() throws Exception {
        Config config = getConfig();
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        HazelcastInstance h2 = nodeFactory.newHazelcastInstance(config);
        IMap imap = h1.getMap("employees");
        imap.addIndex("name", false);
        imap.addIndex("age", true);
        imap.addIndex("active", false);
        for (int i = 0; i < 500; i++) {
            Employee employee = new Employee(i, "name" + i % 100, "city" + (i % 100), i % 60, ((i & 1) == 1), (double) i);
            imap.put(String.valueOf(i), employee);
        }
        assertEquals(2, h1.getCluster().getMembers().size());
        assertEquals(2, h2.getCluster().getMembers().size());
        imap = h2.getMap("employees");
        imap.addIndex("name", false);
        imap.addIndex("age", true);
        imap.addIndex("active", false);
        Collection<Employee> entries = imap.values(new SqlPredicate("name='name3' and city='city3' and age > 2"));
        assertEquals(5, entries.size());
        for (Employee e : entries) {
            assertEquals("name3", e.getName());
            assertEquals("city3", e.getCity());
        }
        entries = imap.values(new SqlPredicate("name LIKE '%name3' and city like '%city3' and age > 2"));
        assertEquals(5, entries.size());
        for (Employee e : entries) {
            assertEquals("name3", e.getName());
            assertEquals("city3", e.getCity());
            assertTrue(e.getAge() > 2);
        }
        entries = imap.values(new SqlPredicate("name LIKE '%name3%' and city like '%city30%'"));
        assertEquals(5, entries.size());
        for (Employee e : entries) {
            assertTrue(e.getName().startsWith("name3"));
            assertTrue(e.getCity().startsWith("city3"));
        }
    }

    @Test(timeout = MINUTE)
    public void testTwoNodesWithIndexes() throws Exception {
        Config config = getConfig();
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        HazelcastInstance h2 = nodeFactory.newHazelcastInstance(config);
        IMap imap = h1.getMap("employees");
        imap.addIndex("name", false);
        imap.addIndex("city", false);
        imap.addIndex("age", true);
        imap.addIndex("active", false);
        for (int i = 0; i < 5000; i++) {
            Employee employee = new Employee(i, "name" + i % 100, "city" + (i % 100), i % 60, ((i & 1) == 1), (double) i);
            imap.put(String.valueOf(i), employee);
        }
        assertEquals(2, h1.getCluster().getMembers().size());
        assertEquals(2, h2.getCluster().getMembers().size());
        imap = h2.getMap("employees");
        imap.addIndex("name", false);
        imap.addIndex("city", false);
        imap.addIndex("age", true);
        imap.addIndex("active", false);
        Collection<Employee> entries = imap.values(new SqlPredicate("name='name3' and city='city3' and age > 2"));
        assertEquals(50, entries.size());
        for (Employee e : entries) {
            assertEquals("name3", e.getName());
            assertEquals("city3", e.getCity());
        }
        entries = imap.values(new SqlPredicate("name LIKE '%name3' and city like '%city3' and age > 2"));
        assertEquals(50, entries.size());
        for (Employee e : entries) {
            assertEquals("name3", e.getName());
            assertEquals("city3", e.getCity());
            assertTrue(e.getAge() > 2);
        }
        entries = imap.values(new SqlPredicate("name LIKE '%name3%' and city like '%city30%'"));
        assertEquals(50, entries.size());
        for (Employee e : entries) {
            assertTrue(e.getName().startsWith("name3"));
            assertTrue(e.getCity().startsWith("city3"));
        }
    }


    @Test(timeout = MINUTE)
    public void testOneMemberWithoutIndex() {
        HazelcastInstance h1 = createHazelcastInstance(getConfig());
        IMap imap = h1.getMap("employees");
        QueryBasicTest.doFunctionalQueryTest(imap);
    }

    @Test(timeout = MINUTE)
    public void testOneMemberWithIndex() {
        HazelcastInstance instance = createHazelcastInstance(getConfig());
        IMap imap = instance.getMap("employees");
        imap.addIndex("name", false);
        imap.addIndex("age", true);
        imap.addIndex("active", false);
        QueryBasicTest.doFunctionalQueryTest(imap);
    }

    @Test(timeout = MINUTE)
    public void testOneMemberSQLWithoutIndex() {
        HazelcastInstance h1 = createHazelcastInstance(getConfig());
        IMap imap = h1.getMap("employees");
        QueryBasicTest.doFunctionalSQLQueryTest(imap);
        Set<Map.Entry> entries = imap.entrySet(new SqlPredicate("active and age>23"));
        assertEquals(27, entries.size());
    }

    @Test(timeout = MINUTE)
    public void testOneMemberSQLWithIndex() {
        HazelcastInstance h1 = createHazelcastInstance(getConfig());
        IMap imap = h1.getMap("employees");
        imap.addIndex("name", false);
        imap.addIndex("age", true);
        imap.addIndex("active", false);
        QueryBasicTest.doFunctionalSQLQueryTest(imap);
    }

    @Test(timeout = MINUTE)
    public void testTwoMembers() {
        Config config = getConfig();
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        HazelcastInstance h2 = nodeFactory.newHazelcastInstance(config);
        IMap imap = h1.getMap("employees");
        QueryBasicTest.doFunctionalQueryTest(imap);
    }

    @Test(timeout = MINUTE)
    public void testTwoMembersWithIndexes() {
        Config config = getConfig();
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        HazelcastInstance h2 = nodeFactory.newHazelcastInstance(config);
        IMap imap = h1.getMap("employees");
        imap.addIndex("name", false);
        imap.addIndex("age", true);
        imap.addIndex("active", false);
        QueryBasicTest.doFunctionalQueryTest(imap);
    }

    @Test(timeout = MINUTE)
    public void testTwoMembersWithIndexesAndShutdown() {
        Config config = getConfig();
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        HazelcastInstance h2 = nodeFactory.newHazelcastInstance(config);
        IMap imap = h1.getMap("employees");
        imap.addIndex("name", false);
        imap.addIndex("age", true);
        imap.addIndex("active", false);
        QueryBasicTest.doFunctionalQueryTest(imap);
        assertEquals(101, imap.size());
        h2.getLifecycleService().shutdown();
        assertEquals(101, imap.size());
        Set<Map.Entry> entries = imap.entrySet(new SqlPredicate("active and age=23"));
        assertEquals(2, entries.size());
        for (Map.Entry entry : entries) {
            Employee c = (Employee) entry.getValue();
            assertEquals(c.getAge(), 23);
            assertTrue(c.isActive());
        }
    }

    @Test(timeout = MINUTE)
    public void testTwoMembersWithIndexesAndShutdown2() {
        Config config = getConfig();
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        HazelcastInstance h2 = nodeFactory.newHazelcastInstance(config);
        IMap imap = h1.getMap("employees");
        imap.addIndex("name", false);
        imap.addIndex("age", true);
        imap.addIndex("active", false);
        QueryBasicTest.doFunctionalQueryTest(imap);
        assertEquals(101, imap.size());
        h1.getLifecycleService().shutdown();
        imap = h2.getMap("employees");
        assertEquals(101, imap.size());
        Set<Map.Entry> entries = imap.entrySet(new SqlPredicate("active and age=23"));
        assertEquals(2, entries.size());
        for (Map.Entry entry : entries) {
            Employee c = (Employee) entry.getValue();
            assertEquals(c.getAge(), 23);
            assertTrue(c.isActive());
        }
    }

    @Test(timeout = MINUTE)
    public void testTwoMembersWithIndexesAndShutdown3() {
        Config config = getConfig();
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        IMap imap = h1.getMap("employees");
        imap.addIndex("name", false);
        imap.addIndex("age", true);
        imap.addIndex("active", false);
        QueryBasicTest.doFunctionalQueryTest(imap);
        assertEquals(101, imap.size());
        HazelcastInstance h2 = nodeFactory.newHazelcastInstance(config);
        assertEquals(101, imap.size());
        h1.getLifecycleService().shutdown();
        imap = h2.getMap("employees");
        assertEquals(101, imap.size());
        Set<Map.Entry> entries = imap.entrySet(new SqlPredicate("active and age=23"));
        assertEquals(2, entries.size());
        for (Map.Entry entry : entries) {
            Employee c = (Employee) entry.getValue();
            assertEquals(c.getAge(), 23);
            assertTrue(c.isActive());
        }
    }

    @Test(timeout = MINUTE)
    public void testSecondMemberAfterAddingIndexes() {
        Config config = getConfig();
        TestHazelcastInstanceFactory nodeFactory = createHazelcastInstanceFactory(2);
        HazelcastInstance h1 = nodeFactory.newHazelcastInstance(config);
        IMap imap = h1.getMap("employees");
        imap.addIndex("name", false);
        imap.addIndex("age", true);
        imap.addIndex("active", false);
        HazelcastInstance h2 = nodeFactory.newHazelcastInstance(config);
        QueryBasicTest.doFunctionalQueryTest(imap);
    }

    @Test
    public void testMapWithIndexAfterShutDown() {
        Config cfg = getConfig();
        String mapName = "default";
        cfg.getMapConfig(mapName).addMapIndexConfig(new MapIndexConfig("typeName", false));

        HazelcastInstance[] instances = createHazelcastInstanceFactory(3).newInstances(cfg);

        final IMap map = instances[0].getMap(mapName);
        final int SAMPLE_SIZE_1 = 100;
        final int SAMPLE_SIZE_2 = 30;
        int TOTAL_SIZE = SAMPLE_SIZE_1 + SAMPLE_SIZE_2;

        for (int i = 0; i < SAMPLE_SIZE_1; i++) {
            map.put(i, new ValueType("type" + i));
        }

        for (int i = SAMPLE_SIZE_1; i < TOTAL_SIZE; i++) {
            map.put(i, new ValueType("typex"));
        }

        Collection typexValues = map.values(new SqlPredicate("typeName = typex"));
        assertEquals(SAMPLE_SIZE_2, typexValues.size());

        instances[1].shutdown();

        assertEquals(TOTAL_SIZE, map.size());
        assertTrueEventually(new AssertTask() {
            public void run() {
                final Collection values = map.values(new SqlPredicate("typeName = typex"));
                assertEquals(SAMPLE_SIZE_2, values.size());
            }
        });

        instances[2].shutdown();

        assertEquals(TOTAL_SIZE, map.size());
        assertTrueEventually(new AssertTask() {
            public void run() {
                final Collection values = map.values(new SqlPredicate("typeName = typex"));
                assertEquals(SAMPLE_SIZE_2, values.size());
            }
        });
    }

    // issue 1404 "to be fixed by issue 1404"
    @Test(timeout = MINUTE)
    public void testQueryAfterInitialLoad() {
        String name = "default";
        Config cfg = getConfig();
        final int size = 100;
        MapStoreConfig mapStoreConfig = new MapStoreConfig();
        mapStoreConfig.setEnabled(true);
        mapStoreConfig.setImplementation(new MapStoreAdapter() {
            @Override
            public Map loadAll(Collection keys) {
                Map map = new HashMap();
                for (Object key : keys) {
                    Employee emp = new Employee();
                    emp.setActive(true);
                    map.put(key, emp);
                }
                return map;
            }

            @Override
            public Set loadAllKeys() {
                Set set = new HashSet();
                for (int i = 0; i < size; i++) {
                    set.add(i);
                }
                return set;
            }
        });
        cfg.getMapConfig(name).setMapStoreConfig(mapStoreConfig);
        HazelcastInstance instance = createHazelcastInstance(cfg);
        final IMap map = instance.getMap(name);

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                Collection values = map.values(new SqlPredicate("active = true"));
                assertEquals(size, values.size());
            }
        });
    }

    @Test
    public void testUnknownPortableField_notCausesQueryException_withoutIndex() {
        final String mapName = randomMapName();

        final Config config = getConfig();
        config.getSerializationConfig().addPortableFactory(666, new PortableFactory() {
            public Portable create(int classId) {
                return new SampleObjects.PortableEmployee();
            }
        });
        HazelcastInstance hazelcastInstance = createHazelcastInstance(config);

        final IMap map = hazelcastInstance.getMap(mapName);


        for (int i = 0; i < 5; i++) {
            map.put(i, new SampleObjects.PortableEmployee(i, "name_" + i));
        }

        Collection values = map.values(new SqlPredicate("notExist = name_0 OR a > 1"));

        assertEquals(3, values.size());
    }

    @Test
    public void testUnknownPortableField_notCausesQueryException_withIndex() {
        final String mapName = "default";

        final Config config = getConfig();
        config.getSerializationConfig().addPortableFactory(666, new PortableFactory() {
            public Portable create(int classId) {
                return new SampleObjects.PortableEmployee();
            }
        });
        config.getMapConfig(mapName)
                .addMapIndexConfig(new MapIndexConfig("notExist", false))
                .addMapIndexConfig(new MapIndexConfig("n", false));

        HazelcastInstance hazelcastInstance = createHazelcastInstance(config);

        final IMap map = hazelcastInstance.getMap(mapName);


        for (int i = 0; i < 5; i++) {
            map.put(i, new SampleObjects.PortableEmployee(i, "name_" + i));
        }

        Collection values = map.values(new SqlPredicate("n = name_2 OR notExist = name_0"));

        assertEquals(1, values.size());
    }
}
